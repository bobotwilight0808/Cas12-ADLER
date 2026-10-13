package com.adler.stripreader;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class StripDetector {
    static final class Detection {
        final boolean ok;
        final Mat canonicalBgr;
        final float[][] corners;
        final String reason;

        Detection(boolean ok, Mat canonicalBgr, float[][] corners, String reason) {
            this.ok = ok;
            this.canonicalBgr = canonicalBgr;
            this.corners = corners;
            this.reason = reason;
        }
    }

    private static final class Component {
        int label;
        double area;
        double width;
        double height;
        double cx;
        double cy;
        double cross;
    }

    private static final class AxisEstimate {
        double cx;
        double cy;
        double ax;
        double ay;
        double mx;
        double my;
        double majorExtent;
        double minorExtent;
    }

    List<Detection> detect(Mat imageBgr) {
        int height = imageBgr.rows();
        int width = imageBgr.cols();
        int maxDimension = Math.max(height, width);
        double scale = maxDimension > 1600 ? 1600.0 / maxDimension : 1.0;
        Mat small = new Mat();
        if (scale < 1.0) {
            Imgproc.resize(imageBgr, small, new Size(), scale, scale, Imgproc.INTER_AREA);
        } else {
            imageBgr.copyTo(small);
        }

        Mat mask = contentMask(small);
        MatOfPoint allPointsMat = new MatOfPoint();
        Core.findNonZero(mask, allPointsMat);
        Point[] allPoints = allPointsMat.toArray();
        if (allPoints.length < 80) {
            release(small, mask, allPointsMat);
            return Collections.singletonList(new Detection(false, null, null,
                    "not_enough_colored_or_printed_pixels"));
        }

        List<Detection> grouped = detectGrouped(imageBgr, mask, scale);
        if (grouped.size() > 1) {
            sortSpatially(grouped);
            release(small, mask, allPointsMat);
            return grouped;
        }

        Detection global = detectionFromPoints(imageBgr, allPoints, scale);
        release(small, mask, allPointsMat);
        if (global.ok) return Collections.singletonList(global);
        if (grouped.size() == 1) return grouped;
        return Collections.singletonList(global);
    }

    private List<Detection> detectGrouped(Mat imageBgr, Mat mask, double scale) {
        Mat labels = new Mat();
        Mat stats = new Mat();
        Mat centroids = new Mat();
        int count = Imgproc.connectedComponentsWithStats(mask, labels, stats, centroids, 8, CvType.CV_32S);
        List<Component> components = componentInfos(count, stats, centroids);
        if (components.size() < 2) {
            release(labels, stats, centroids);
            return new ArrayList<>();
        }

        double[] axis = estimateComponentAxis(components);
        List<List<Component>> groups = clusterByCrossAxis(components, axis, mask.rows(), mask.cols());
        List<Detection> detections = new ArrayList<>();
        for (List<Component> group : groups) {
            if (group.size() < 2) continue;
            Set<Integer> groupLabels = new HashSet<>();
            for (Component component : group) groupLabels.add(component.label);
            Mat groupMask = Mat.zeros(labels.size(), CvType.CV_8U);
            Mat componentMask = new Mat();
            for (int label : groupLabels) {
                Core.compare(labels, new Scalar(label), componentMask, Core.CMP_EQ);
                Core.bitwise_or(groupMask, componentMask, groupMask);
            }
            MatOfPoint pointsMat = new MatOfPoint();
            Core.findNonZero(groupMask, pointsMat);
            Point[] points = pointsMat.toArray();
            if (points.length >= 80) {
                Detection detection = detectionFromPoints(imageBgr, points, scale);
                if (detection.ok) detections.add(detection);
            }
            release(groupMask, componentMask, pointsMat);
        }
        release(labels, stats, centroids);
        sortSpatially(detections);
        return detections;
    }

    private Detection detectionFromPoints(Mat imageBgr, Point[] points, double scale) {
        AxisEstimate estimate = robustAxis(points);
        if (estimate.majorExtent < 80.0
                || estimate.majorExtent / Math.max(estimate.minorExtent, 1.0) < 2.5) {
            return new Detection(false, null, null, "content_pixels_do_not_form_a_long_strip");
        }

        double stripLength = Math.max(
                Math.max(estimate.majorExtent * 1.45, estimate.minorExtent * 8.0),
                260.0 * scale
        );
        double stripWidth = Math.max(
                Math.max(estimate.minorExtent * 2.25, stripLength * 0.105),
                34.0 * scale
        );
        stripLength = Math.min(stripLength, Math.max(imageBgr.rows(), imageBgr.cols()) * scale * 1.05);
        stripWidth = Math.min(stripWidth, stripLength * 0.22);

        float[][] corners = rectangleCorners(estimate, stripLength, stripWidth, scale);
        Mat canonical = warpRect(imageBgr, corners);
        orientSamplePadDown(canonical);
        return new Detection(true, canonical, corners, "ok");
    }

    private Mat contentMask(Mat imageBgr) {
        Mat hsv = new Mat();
        Mat gray = new Mat();
        Imgproc.cvtColor(imageBgr, hsv, Imgproc.COLOR_BGR2HSV);
        Imgproc.cvtColor(imageBgr, gray, Imgproc.COLOR_BGR2GRAY);
        Mat saturated = new Mat();
        Core.inRange(hsv, new Scalar(0, 46, 36), new Scalar(179, 255, 255), saturated);

        Mat mask = new Mat();
        if (Core.countNonZero(saturated) < 80) {
            double[] grayValues = new double[(int) gray.total()];
            byte[] grayBytes = new byte[grayValues.length];
            gray.get(0, 0, grayBytes);
            for (int i = 0; i < grayBytes.length; i++) grayValues[i] = grayBytes[i] & 0xff;
            double threshold = Statistics.percentile(grayValues, 4.0);
            Mat darkPrint = new Mat();
            Imgproc.threshold(gray, darkPrint, threshold, 255, Imgproc.THRESH_BINARY_INV);
            Core.bitwise_or(saturated, darkPrint, mask);
            darkPrint.release();
        } else {
            saturated.copyTo(mask);
        }

        Mat openKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(3, 3));
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, openKernel);
        Mat reasonable = keepReasonableComponents(mask);
        Mat closeKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(11, 11));
        Imgproc.morphologyEx(reasonable, reasonable, Imgproc.MORPH_CLOSE, closeKernel, new Point(-1, -1), 1);

        release(hsv, gray, saturated, mask, openKernel, closeKernel);
        return reasonable;
    }

    private Mat keepReasonableComponents(Mat mask) {
        Mat labels = new Mat();
        Mat stats = new Mat();
        Mat centroids = new Mat();
        int count = Imgproc.connectedComponentsWithStats(mask, labels, stats, centroids, 8, CvType.CV_32S);
        Mat output = Mat.zeros(mask.size(), CvType.CV_8U);
        Mat componentMask = new Mat();
        double imageArea = mask.rows() * (double) mask.cols();
        for (int label = 1; label < count; label++) {
            double area = stats.get(label, Imgproc.CC_STAT_AREA)[0];
            if (area < 8 || area > imageArea * 0.05) continue;
            Core.compare(labels, new Scalar(label), componentMask, Core.CMP_EQ);
            output.setTo(new Scalar(255), componentMask);
        }
        release(labels, stats, centroids, componentMask);
        return output;
    }

    private List<Component> componentInfos(int count, Mat stats, Mat centroids) {
        List<Component> output = new ArrayList<>();
        for (int label = 1; label < count; label++) {
            double area = stats.get(label, Imgproc.CC_STAT_AREA)[0];
            if (area < 20) continue;
            Component component = new Component();
            component.label = label;
            component.area = area;
            component.width = stats.get(label, Imgproc.CC_STAT_WIDTH)[0];
            component.height = stats.get(label, Imgproc.CC_STAT_HEIGHT)[0];
            component.cx = centroids.get(label, 0)[0];
            component.cy = centroids.get(label, 1)[0];
            output.add(component);
        }
        return output;
    }

    private double[] estimateComponentAxis(List<Component> components) {
        double horizontal = 0.0;
        double vertical = 0.0;
        for (Component component : components) {
            horizontal += component.area * component.width;
            vertical += component.area * component.height;
        }
        return horizontal >= vertical ? new double[]{1.0, 0.0} : new double[]{0.0, 1.0};
    }

    private List<List<Component>> clusterByCrossAxis(
            List<Component> components,
            double[] axis,
            int imageHeight,
            int imageWidth
    ) {
        double minorX = -axis[1];
        double minorY = axis[0];
        double[] crossSizes = new double[components.size()];
        for (int i = 0; i < components.size(); i++) {
            Component component = components.get(i);
            component.cross = component.cx * minorX + component.cy * minorY;
            crossSizes[i] = Math.abs(axis[0]) >= Math.abs(axis[1])
                    ? component.height : component.width;
        }
        double gapThreshold = Math.max(
                Math.max(40.0, Statistics.median(crossSizes) * 4.0),
                Math.min(imageHeight, imageWidth) * 0.045
        );
        components.sort(Comparator.comparingDouble(component -> component.cross));
        List<List<Component>> groups = new ArrayList<>();
        groups.add(new ArrayList<>());
        groups.get(0).add(components.get(0));
        for (int i = 1; i < components.size(); i++) {
            Component component = components.get(i);
            List<Component> current = groups.get(groups.size() - 1);
            if (component.cross - current.get(current.size() - 1).cross > gapThreshold) {
                current = new ArrayList<>();
                groups.add(current);
            }
            current.add(component);
        }
        groups.removeIf(group -> {
            double area = 0.0;
            for (Component component : group) area += component.area;
            return area < 80.0;
        });
        return groups;
    }

    private AxisEstimate robustAxis(Point[] points) {
        AxisEstimate output = new AxisEstimate();
        double meanX = 0.0;
        double meanY = 0.0;
        for (Point point : points) {
            meanX += point.x;
            meanY += point.y;
        }
        meanX /= points.length;
        meanY /= points.length;

        double covarianceXX = 0.0;
        double covarianceXY = 0.0;
        double covarianceYY = 0.0;
        for (Point point : points) {
            double dx = point.x - meanX;
            double dy = point.y - meanY;
            covarianceXX += dx * dx;
            covarianceXY += dx * dy;
            covarianceYY += dy * dy;
        }
        double angle = 0.5 * Math.atan2(2.0 * covarianceXY, covarianceXX - covarianceYY);
        output.ax = Math.cos(angle);
        output.ay = Math.sin(angle);
        output.mx = -output.ay;
        output.my = output.ax;

        double[] major = new double[points.length];
        double[] minor = new double[points.length];
        for (int i = 0; i < points.length; i++) {
            double dx = points[i].x - meanX;
            double dy = points[i].y - meanY;
            major[i] = dx * output.ax + dy * output.ay;
            minor[i] = dx * output.mx + dy * output.my;
        }
        double majorLow = Statistics.percentile(major, 1.0);
        double majorHigh = Statistics.percentile(major, 99.0);
        double minorLow = Statistics.percentile(minor, 1.0);
        double minorHigh = Statistics.percentile(minor, 99.0);
        output.cx = meanX + output.ax * (majorLow + majorHigh) / 2.0
                + output.mx * (minorLow + minorHigh) / 2.0;
        output.cy = meanY + output.ay * (majorLow + majorHigh) / 2.0
                + output.my * (minorLow + minorHigh) / 2.0;
        output.majorExtent = majorHigh - majorLow;
        output.minorExtent = minorHigh - minorLow;
        return output;
    }

    private float[][] rectangleCorners(AxisEstimate axis, double length, double width, double scale) {
        double halfLength = length / 2.0;
        double halfWidth = width / 2.0;
        double[][] signs = {{-1, -1}, {-1, 1}, {1, 1}, {1, -1}};
        float[][] output = new float[4][2];
        for (int i = 0; i < 4; i++) {
            output[i][0] = (float) ((axis.cx + signs[i][0] * axis.ax * halfLength
                    + signs[i][1] * axis.mx * halfWidth) / scale);
            output[i][1] = (float) ((axis.cy + signs[i][0] * axis.ay * halfLength
                    + signs[i][1] * axis.my * halfWidth) / scale);
        }
        return output;
    }

    private Mat warpRect(Mat imageBgr, float[][] corners) {
        MatOfPoint2f source = new MatOfPoint2f(
                new Point(corners[0][0], corners[0][1]),
                new Point(corners[1][0], corners[1][1]),
                new Point(corners[2][0], corners[2][1]),
                new Point(corners[3][0], corners[3][1])
        );
        MatOfPoint2f destination = new MatOfPoint2f(
                new Point(0, 0),
                new Point(StripConfig.TEMPLATE_WIDTH - 1, 0),
                new Point(StripConfig.TEMPLATE_WIDTH - 1, StripConfig.TEMPLATE_HEIGHT - 1),
                new Point(0, StripConfig.TEMPLATE_HEIGHT - 1)
        );
        Mat transform = Imgproc.getPerspectiveTransform(source, destination);
        Mat output = new Mat();
        Imgproc.warpPerspective(imageBgr, output, transform,
                new Size(StripConfig.TEMPLATE_WIDTH, StripConfig.TEMPLATE_HEIGHT));
        release(source, destination, transform);
        return output;
    }

    private void orientSamplePadDown(Mat canonicalBgr) {
        Mat hsv = new Mat();
        Imgproc.cvtColor(canonicalBgr, hsv, Imgproc.COLOR_BGR2HSV);
        int width = hsv.cols();
        int height = hsv.rows();
        int x1 = (int) (width * 0.25);
        int x2 = (int) (width * 0.75);
        double[] blueProfile = new double[height];
        double[] redProfile = new double[height];
        byte[] row = new byte[width * 3];
        double topBlue = 0.0;
        double bottomBlue = 0.0;
        for (int y = 0; y < height; y++) {
            hsv.get(y, 0, row);
            for (int x = x1; x < x2; x++) {
                int hue = row[x * 3] & 0xff;
                int saturation = row[x * 3 + 1] & 0xff;
                boolean blue = hue > 85 && hue < 135 && saturation > 60;
                boolean red = (hue < 12 || hue > 165) && saturation > 45;
                if (blue) {
                    blueProfile[y] += 1.0;
                    if (y < height / 3) topBlue += saturation;
                    if (y >= height * 2 / 3) bottomBlue += saturation;
                }
                if (red) redProfile[y] += 1.0;
            }
        }

        boolean rotate;
        if (Statistics.max(blueProfile) > 5.0 && Statistics.max(redProfile) > 2.0) {
            double[] smoothBlue = smooth(blueProfile, 21);
            double[] smoothRed = smooth(redProfile, 15);
            rotate = Statistics.argMax(smoothBlue) < Statistics.argMax(smoothRed);
        } else {
            double denominator = Math.max(1.0, (height / 3.0) * width);
            rotate = topBlue / denominator > bottomBlue / denominator * 1.15;
        }
        if (rotate) Core.rotate(canonicalBgr, canonicalBgr, Core.ROTATE_180);
        hsv.release();
    }

    static double[] smooth(double[] values, int kernelSize) {
        Mat input = new Mat(values.length, 1, CvType.CV_32F);
        float[] data = new float[values.length];
        for (int i = 0; i < values.length; i++) data[i] = (float) values[i];
        input.put(0, 0, data);
        Mat output = new Mat();
        Imgproc.GaussianBlur(input, output, new Size(1, kernelSize), 0);
        float[] smoothed = new float[values.length];
        output.get(0, 0, smoothed);
        double[] result = new double[values.length];
        for (int i = 0; i < values.length; i++) result[i] = smoothed[i];
        release(input, output);
        return result;
    }

    private void sortSpatially(List<Detection> detections) {
        detections.sort((first, second) -> {
            double firstY = center(first.corners, 1);
            double secondY = center(second.corners, 1);
            int yComparison = Double.compare(firstY, secondY);
            return yComparison != 0 ? yComparison
                    : Double.compare(center(first.corners, 0), center(second.corners, 0));
        });
    }

    private double center(float[][] corners, int dimension) {
        double output = 0.0;
        for (float[] corner : corners) output += corner[dimension];
        return output / corners.length;
    }

    private static void release(Mat... mats) {
        for (Mat mat : mats) {
            if (mat != null) mat.release();
        }
    }
}
