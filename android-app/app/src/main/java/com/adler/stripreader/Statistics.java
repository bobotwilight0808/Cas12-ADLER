package com.adler.stripreader;

import java.util.Arrays;

final class Statistics {
    static double percentile(double[] values, double percentile) {
        if (values.length == 0) return 0.0;
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        if (sorted.length == 1) return sorted[0];
        double position = Math.max(0.0, Math.min(100.0, percentile)) / 100.0 * (sorted.length - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        double fraction = position - lower;
        return sorted[lower] * (1.0 - fraction) + sorted[upper] * fraction;
    }

    static double median(double[] values) {
        return percentile(values, 50.0);
    }

    static double standardDeviation(double[] values) {
        if (values.length == 0) return 0.0;
        double mean = 0.0;
        for (double value : values) mean += value;
        mean /= values.length;
        double squared = 0.0;
        for (double value : values) {
            double delta = value - mean;
            squared += delta * delta;
        }
        return Math.sqrt(squared / values.length);
    }

    static int argMax(double[] values) {
        int index = 0;
        for (int i = 1; i < values.length; i++) {
            if (values[i] > values[index]) index = i;
        }
        return index;
    }

    static double max(double[] values) {
        if (values.length == 0) return 0.0;
        double output = values[0];
        for (int i = 1; i < values.length; i++) output = Math.max(output, values[i]);
        return output;
    }

    private Statistics() {}
}
