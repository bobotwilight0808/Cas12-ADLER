package com.adler.stripreader;

import org.opencv.core.Mat;

import java.util.ArrayList;
import java.util.List;

final class StripReader {
    private final StripDetector detector = new StripDetector();
    private final StripAnalyzer analyzer = new StripAnalyzer();

    AnalysisResult analyze(Mat imageBgr, String stripType) {
        if (imageBgr == null || imageBgr.empty()) {
            return AnalysisResult.unreadable(stripType, "image_is_empty");
        }
        StripConfig.Template template = StripConfig.forType(stripType);
        List<StripDetector.Detection> detections = detector.detect(imageBgr);
        List<AnalysisResult.StripReading> strips = new ArrayList<>();
        String failureReason = "not_enough_colored_or_printed_pixels";
        for (int index = 0; index < detections.size(); index++) {
            StripDetector.Detection detection = detections.get(index);
            if (!detection.ok || detection.canonicalBgr == null) {
                failureReason = detection.reason;
                continue;
            }
            Mat canonical = analyzer.chooseOrientation(detection.canonicalBgr, template);
            strips.add(analyzer.analyze(canonical, template, index, detection.corners));
            canonical.release();
        }
        if (strips.isEmpty()) return AnalysisResult.unreadable(stripType, failureReason);
        return AnalysisResult.aggregate(stripType, strips);
    }
}
