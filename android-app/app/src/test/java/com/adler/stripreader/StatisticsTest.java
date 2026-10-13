package com.adler.stripreader;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class StatisticsTest {
    @Test
    public void percentileMatchesNumpyLinearInterpolation() {
        assertEquals(2.5, Statistics.percentile(new double[]{0.0, 10.0}, 25.0), 1e-9);
        assertEquals(5.0, Statistics.median(new double[]{10.0, 0.0}), 1e-9);
    }

    @Test
    public void standardDeviationUsesPopulationDefinition() {
        assertEquals(Math.sqrt(1.25),
                Statistics.standardDeviation(new double[]{1.0, 2.0, 3.0, 4.0}), 1e-9);
    }
}
