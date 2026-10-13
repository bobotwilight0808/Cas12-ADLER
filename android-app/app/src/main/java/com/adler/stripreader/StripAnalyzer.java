package com.adler.stripreader;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

final class StripAnalyzer {
    private static final class OrientationScores {
        double targetTotal;
        double controlTotal;
    }

    Mat chooseOrientation(Mat canonicalBgr, StripConfig.Template template) {
        if (!"dual".equals(template.stripType)) return canonicalBgr;
        Mat rotated = new Mat();
        Core.rotate(canonicalBgr, rotated, Core.ROTATE_180);
        OrientationScores currentScores = dualOrientationScores(canonicalBgr, template);
        OrientationScores rotatedScores = dualOrientationScores(rotated, template);
        double currentDecision = orientationDecision(currentScores, rotatedScores);
        double rotatedDecision = orientationDecision(rotatedScores, currentScores);
        if (rotatedDecision > currentDecision * 1.25) {
            canonicalBgr.release();
            return rotated;
        }
        rotated.release();
        return canonicalBgr;
    }

    AnalysisResult.StripReading analyze(
            Mat canonicalBgr,
            StripConfig.Template template,
            int stripIndex,
            float[][] corners
    ) {
        double[] profile = lineProfile(canonicalBgr);
        Map<String, AnalysisResult.LineReading> lineScores = new LinkedHashMap<>();
        for (Map.Entry<String, StripConfig.LineWindow> entry : template.lines.entrySet()) {
            double threshold = "C".equals(entry.getKey())
                    ? StripConfig.CONTROL_THRESHOLD : StripConfig.TARGET_THRESHOLD;
            lineScores.put(entry.getKey(), scoreLine(
                    entry.getKey(),
                    profile,
                    entry.getValue().y,
                    entry.getValue().halfHeight,
                    threshold,
                    entry.getValue().peakTolerance
            ));
        }

        boolean anyLinePresent = false;
        boolean lowConfidence = false;
        for (AnalysisResult.LineReading line : lineScores.values()) {
            anyLinePresent |= line.present;
            double margin = Math.max(line.threshold * StripConfig.LOW_CONFIDENCE_MARGIN, 2.0);
            lowConfidence |= Math.abs(line.score - line.threshold) <= margin;
        }

        String validity = anyLinePresent ? "valid" : "invalid_no_lines";
        String singleResult = null;
        String t1Result = null;
        String t2Result = null;
        if (anyLinePresent && "single".equals(template.stripType)) {
            singleResult = lineScores.get("T").present ? "positive" : "negative";
        } else if (anyLinePresent) {
            t1Result = lineScores.get("T1").present ? "positive" : "negative";
            t2Result = lineScores.get("T2").present ? "positive" : "negative";
        }

        return new AnalysisResult.StripReading(
                stripIndex,
                validity,
                lowConfidence ? "low" : "high",
                singleResult,
                t1Result,
                t2Result,
                lineScores,
                corners
        );
    }

    static AnalysisResult.LineReading scoreLine(
            String name,
            double[] profile,
            double yRatio,
            double halfHeightRatio,
            double threshold,
            Double peakTolerance
    ) {
        int height = profile.length;
        int center = (int) Math.round(yRatio * height);
        int half = Math.max(4, (int) Math.round(halfHeightRatio * height));
        int y1 = Math.max(0, center - half);
        int y2 = Math.min(height, center + half + 1);
        int backgroundGap = Math.max(3, half / 2);
        int backgroundHalf = Math.max(6, half);
        int background1Start = Math.max(0, y1 - backgroundGap - backgroundHalf);
        int background1End = Math.max(0, y1 - backgroundGap);
        int background2Start = Math.min(height, y2 + backgroundGap);
        int background2End = Math.min(height, y2 + backgroundGap + backgroundHalf);

        double[] background = concatenate(
                Arrays.copyOfRange(profile, background1Start, background1End),
                Arrays.copyOfRange(profile, background2Start, background2End)
        );
        if (background.length == 0) background = profile.clone();
        double[] segment = Arrays.copyOfRange(profile, y1, y2);
        int peakOffset = Statistics.argMax(segment);
        int peakY = y1 + peakOffset;
        double score = segment[peakOffset] - Statistics.median(background);
        double noise = Statistics.standardDeviation(background) + 1e-6;
        double peakYRatio = peakY / (double) Math.max(height - 1, 1);
        boolean peakInPosition = peakTolerance == null
                || Math.abs(peakYRatio - yRatio) <= peakTolerance;
        return new AnalysisResult.LineReading(
                name,
                score,
                score / noise,
                peakY,
                peakYRatio,
                threshold,
                score >= threshold && peakInPosition
        );
    }

    private double[] lineProfile(Mat canonicalBgr) {
        Mat hsv = new Mat();
        Mat gray = new Mat();
        Imgproc.cvtColor(canonicalBgr, hsv, Imgproc.COLOR_BGR2HSV);
        Imgproc.cvtColor(canonicalBgr, gray, Imgproc.COLOR_BGR2GRAY);
        int height = canonicalBgr.rows();
        int width = canonicalBgr.cols();
        int x1 = Math.max(0, (int) (width * 0.14));
        int x2 = Math.min(width, (int) (width * 0.86));
        int centralWidth = x2 - x1;
        double[] profile = new double[height];
        byte[] hsvRow = new byte[width * 3];
        byte[] grayRow = new byte[width];
        double[] centralGray = new double[centralWidth];
        double[] scores = new double[centralWidth];

        for (int y = 0; y < height; y++) {
            hsv.get(y, 0, hsvRow);
            gray.get(y, 0, grayRow);
            for (int x = x1; x < x2; x++) centralGray[x - x1] = grayRow[x] & 0xff;
            double gray80 = Statistics.percentile(centralGray, 80.0);
            for (int x = x1; x < x2; x++) {
                int index = x - x1;
                double saturation = hsvRow[x * 3 + 1] & 0xff;
                double darkness = Math.max(0.0, gray80 - (grayRow[x] & 0xff));
                scores[index] = 0.72 * saturation + 0.28 * darkness;
            }
            profile[y] = Statistics.percentile(scores, 92.0);
        }
        hsv.release();
        gray.release();
        return StripDetector.smooth(profile, 17);
    }

    private OrientationScores dualOrientationScores(Mat canonicalBgr, StripConfig.Template template) {
        Map<String, double[]> profiles = colorProfiles(canonicalBgr);
        OrientationScores output = new OrientationScores();
        for (Map.Entry<String, StripConfig.LineWindow> entry : template.lines.entrySet()) {
            String color = "T2".equals(entry.getKey()) ? "red"
                    : "T1".equals(entry.getKey()) ? "blue" : "any";
            double score = Math.max(0.0, windowScore(
                    profiles.get(color), entry.getValue().y, entry.getValue().halfHeight));
            if ("C".equals(entry.getKey())) output.controlTotal += score;
            else output.targetTotal += score;
        }
        return output;
    }

    private double orientationDecision(OrientationScores score, OrientationScores other) {
        return Math.max(score.targetTotal, other.targetTotal) >= 8.0
                ? score.targetTotal : score.controlTotal;
    }

    private Map<String, double[]> colorProfiles(Mat canonicalBgr) {
        Mat hsv = new Mat();
        Mat gray = new Mat();
        Imgproc.cvtColor(canonicalBgr, hsv, Imgproc.COLOR_BGR2HSV);
        Imgproc.cvtColor(canonicalBgr, gray, Imgproc.COLOR_BGR2GRAY);
        int height = canonicalBgr.rows();
        int width = canonicalBgr.cols();
        int x1 = Math.max(0, (int) (width * 0.14));
        int x2 = Math.min(width, (int) (width * 0.86));
        int centralWidth = x2 - x1;
        double[] anyProfile = new double[height];
        double[] redProfile = new double[height];
        double[] blueProfile = new double[height];
        byte[] hsvRow = new byte[width * 3];
        byte[] grayRow = new byte[width];
        double[] centralGray = new double[centralWidth];
        double[] anyScores = new double[centralWidth];
        double[] redScores = new double[centralWidth];
        double[] blueScores = new double[centralWidth];

        for (int y = 0; y < height; y++) {
            hsv.get(y, 0, hsvRow);
            gray.get(y, 0, grayRow);
            for (int x = x1; x < x2; x++) centralGray[x - x1] = grayRow[x] & 0xff;
            double gray80 = Statistics.percentile(centralGray, 80.0);
            for (int x = x1; x < x2; x++) {
                int index = x - x1;
                int hue = hsvRow[x * 3] & 0xff;
                double saturation = hsvRow[x * 3 + 1] & 0xff;
                double darkness = Math.max(0.0, gray80 - (grayRow[x] & 0xff));
                anyScores[index] = 0.72 * saturation + 0.28 * darkness;
                redScores[index] = hue < 12 || hue > 165 ? saturation : 0.0;
                blueScores[index] = hue > 85 && hue < 135 ? saturation : 0.0;
            }
            anyProfile[y] = Statistics.percentile(anyScores, 92.0);
            redProfile[y] = Statistics.percentile(redScores, 92.0);
            blueProfile[y] = Statistics.percentile(blueScores, 92.0);
        }
        hsv.release();
        gray.release();
        Map<String, double[]> output = new LinkedHashMap<>();
        output.put("any", StripDetector.smooth(anyProfile, 17));
        output.put("red", StripDetector.smooth(redProfile, 17));
        output.put("blue", StripDetector.smooth(blueProfile, 17));
        return output;
    }

    private double windowScore(double[] profile, double yRatio, double halfHeightRatio) {
        int height = profile.length;
        int center = (int) Math.round(yRatio * height);
        int half = Math.max(4, (int) Math.round(halfHeightRatio * height));
        int y1 = Math.max(0, center - half);
        int y2 = Math.min(height, center + half + 1);
        int backgroundGap = Math.max(3, half / 2);
        int backgroundHalf = Math.max(6, half);
        double[] background = concatenate(
                Arrays.copyOfRange(profile,
                        Math.max(0, y1 - backgroundGap - backgroundHalf),
                        Math.max(0, y1 - backgroundGap)),
                Arrays.copyOfRange(profile,
                        Math.min(height, y2 + backgroundGap),
                        Math.min(height, y2 + backgroundGap + backgroundHalf))
        );
        if (background.length == 0) background = profile.clone();
        double[] segment = Arrays.copyOfRange(profile, y1, y2);
        return Statistics.max(segment) - Statistics.median(background);
    }

    private static double[] concatenate(double[] first, double[] second) {
        double[] output = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, output, first.length, second.length);
        return output;
    }
}
