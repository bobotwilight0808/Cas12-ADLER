package com.adler.stripreader;

import java.util.LinkedHashMap;
import java.util.Map;

final class StripConfig {
    static final int TEMPLATE_WIDTH = 120;
    static final int TEMPLATE_HEIGHT = 900;
    static final double CONTROL_THRESHOLD = 1.0;
    static final double TARGET_THRESHOLD = 6.0;
    static final double LOW_CONFIDENCE_MARGIN = 0.25;

    static final class LineWindow {
        final double y;
        final double halfHeight;
        final Double peakTolerance;

        LineWindow(double y, double halfHeight, Double peakTolerance) {
            this.y = y;
            this.halfHeight = halfHeight;
            this.peakTolerance = peakTolerance;
        }
    }

    static final class Template {
        final String stripType;
        final Map<String, LineWindow> lines;

        Template(String stripType, Map<String, LineWindow> lines) {
            this.stripType = stripType;
            this.lines = lines;
        }
    }

    static Template forType(String stripType) {
        if ("single".equals(stripType)) {
            Map<String, LineWindow> lines = new LinkedHashMap<>();
            lines.put("T", new LineWindow(0.46, 0.035, null));
            lines.put("C", new LineWindow(0.52, 0.035, null));
            return new Template("single", lines);
        }
        Map<String, LineWindow> lines = new LinkedHashMap<>();
        lines.put("T2", new LineWindow(0.46, 0.030, 0.016));
        lines.put("T1", new LineWindow(0.52, 0.035, 0.040));
        lines.put("C", new LineWindow(0.60, 0.060, null));
        return new Template("dual", lines);
    }

    private StripConfig() {}
}
