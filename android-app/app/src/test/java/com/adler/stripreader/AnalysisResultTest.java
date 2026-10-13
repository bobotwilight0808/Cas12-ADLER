package com.adler.stripreader;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;

public final class AnalysisResultTest {
    @Test
    public void anyPositiveStripWinsAggregate() {
        AnalysisResult result = AnalysisResult.aggregate("dual", Arrays.asList(
                syntheticStrip(0, "dual", "valid", "high", false, false),
                syntheticStrip(1, "dual", "valid", "high", true, false)
        ));
        assertEquals("positive", result.t1Result);
        assertEquals("negative", result.t2Result);
        assertEquals("valid", result.validity);
    }

    @Test
    public void allNoLineStripsProduceInvalidAggregate() {
        AnalysisResult result = AnalysisResult.aggregate("single", Arrays.asList(
                syntheticStrip(0, "single", "invalid_no_lines", "high", false, false),
                syntheticStrip(1, "single", "invalid_no_lines", "low", false, false)
        ));
        assertEquals("invalid_no_lines", result.validity);
        assertEquals("low", result.confidence);
    }

    private static AnalysisResult.StripReading syntheticStrip(
            int index, String stripType, String validity, String confidence,
            boolean firstTargetPositive, boolean secondTargetPositive) {
        if ("single".equals(stripType)) {
            return new AnalysisResult.StripReading(index, validity, confidence,
                    firstTargetPositive ? "positive" : "negative",
                    null, null, Collections.emptyMap(), null);
        }
        return new AnalysisResult.StripReading(index, validity, confidence, null,
                firstTargetPositive ? "positive" : "negative",
                secondTargetPositive ? "positive" : "negative", Collections.emptyMap(), null);
    }
}
