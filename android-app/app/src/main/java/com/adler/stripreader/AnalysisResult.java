package com.adler.stripreader;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class AnalysisResult {
    enum Tone { POSITIVE, NEGATIVE, WARNING }

    static final class LineReading {
        final String name;
        final double score;
        final double snr;
        final int peakY;
        final double peakYRatio;
        final double threshold;
        final boolean present;

        LineReading(String name, double score, double snr, int peakY, double peakYRatio,
                    double threshold, boolean present) {
            this.name = name;
            this.score = score;
            this.snr = snr;
            this.peakY = peakY;
            this.peakYRatio = peakYRatio;
            this.threshold = threshold;
            this.present = present;
        }
    }

    static final class StripReading {
        final int index;
        final String validity;
        final String confidence;
        final String singleResult;
        final String t1Result;
        final String t2Result;
        final Map<String, LineReading> lines;
        final float[][] corners;

        StripReading(int index, String validity, String confidence, String singleResult,
                     String t1Result, String t2Result, Map<String, LineReading> lines,
                     float[][] corners) {
            this.index = index;
            this.validity = validity;
            this.confidence = confidence;
            this.singleResult = singleResult;
            this.t1Result = t1Result;
            this.t2Result = t2Result;
            this.lines = lines;
            this.corners = corners;
        }
    }

    final String readability;
    final String stripType;
    final String validity;
    final String confidence;
    final String singleResult;
    final String t1Result;
    final String t2Result;
    final int stripCount;
    final String reason;
    final List<StripReading> strips;

    private AnalysisResult(String readability, String stripType, String validity, String confidence,
                           String singleResult, String t1Result, String t2Result, int stripCount,
                           String reason, List<StripReading> strips) {
        this.readability = readability;
        this.stripType = stripType;
        this.validity = validity;
        this.confidence = confidence;
        this.singleResult = singleResult;
        this.t1Result = t1Result;
        this.t2Result = t2Result;
        this.stripCount = stripCount;
        this.reason = reason;
        this.strips = strips;
    }

    static AnalysisResult unreadable(String stripType, String reason) {
        return new AnalysisResult("unreadable_photo", stripType, "not_evaluated", "low",
                null, null, null, 0, reason, new ArrayList<>());
    }

    static AnalysisResult aggregate(String stripType, List<StripReading> strips) {
        if (strips.isEmpty()) return unreadable(stripType, "no_readable_strips");
        boolean allInvalid = true;
        boolean lowConfidence = false;
        boolean anySinglePositive = false;
        boolean allSingleNegative = true;
        boolean anyT1Positive = false;
        boolean allT1Negative = true;
        boolean anyT2Positive = false;
        boolean allT2Negative = true;

        for (StripReading strip : strips) {
            allInvalid &= "invalid_no_lines".equals(strip.validity);
            lowConfidence |= "low".equals(strip.confidence);
            anySinglePositive |= "positive".equals(strip.singleResult);
            allSingleNegative &= "negative".equals(strip.singleResult);
            anyT1Positive |= "positive".equals(strip.t1Result);
            allT1Negative &= "negative".equals(strip.t1Result);
            anyT2Positive |= "positive".equals(strip.t2Result);
            allT2Negative &= "negative".equals(strip.t2Result);
        }

        String singleResult = null;
        String t1Result = null;
        String t2Result = null;
        if ("single".equals(stripType)) {
            singleResult = anySinglePositive ? "positive" : allSingleNegative ? "negative" : null;
        } else {
            t1Result = anyT1Positive ? "positive" : allT1Negative ? "negative" : null;
            t2Result = anyT2Positive ? "positive" : allT2Negative ? "negative" : null;
        }

        return new AnalysisResult("ok", stripType,
                allInvalid ? "invalid_no_lines" : "valid",
                lowConfidence ? "low" : "high",
                singleResult, t1Result, t2Result, strips.size(), "ok", strips);
    }

    String displayTitle() {
        if (!"ok".equals(readability)) return "IMAGE UNREADABLE";
        if ("invalid_no_lines".equals(validity)) return "INVALID TEST";
        if ("single".equals(stripType)) {
            return "positive".equals(singleResult) ? "POSITIVE" : "NEGATIVE";
        }
        boolean t1 = "positive".equals(t1Result);
        boolean t2 = "positive".equals(t2Result);
        if (t1 && t2) return "T1 / T2 POSITIVE";
        if (t1) return "T1 POSITIVE";
        if (t2) return "T2 POSITIVE";
        return "NEGATIVE";
    }

    Tone tone() {
        if (!"ok".equals(readability) || !"valid".equals(validity)) return Tone.WARNING;
        if ("single".equals(stripType)) {
            return "positive".equals(singleResult) ? Tone.POSITIVE : Tone.NEGATIVE;
        }
        return "positive".equals(t1Result) || "positive".equals(t2Result)
                ? Tone.POSITIVE : Tone.NEGATIVE;
    }

    List<float[][]> cornerSets() {
        List<float[][]> output = new ArrayList<>();
        for (StripReading strip : strips) {
            if (strip.corners != null) output.add(strip.corners);
        }
        return output;
    }
}
