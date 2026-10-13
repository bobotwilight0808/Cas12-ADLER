package com.adler.stripreader;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class StripAnalyzerTest {
    @Test
    public void lineAtTemplateCenterIsPresent() {
        double[] profile = new double[100];
        profile[46] = 10.0;
        AnalysisResult.LineReading reading = StripAnalyzer.scoreLine(
                "T", profile, 0.46, 0.035, 6.0, null);
        assertTrue(reading.present);
    }

    @Test
    public void peakToleranceRejectsStrongOffCenterLine() {
        double[] profile = new double[100];
        profile[50] = 10.0;
        AnalysisResult.LineReading reading = StripAnalyzer.scoreLine(
                "T2", profile, 0.46, 0.06, 6.0, 0.016);
        assertFalse(reading.present);
    }
}
