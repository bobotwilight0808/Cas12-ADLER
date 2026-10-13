package com.adler.stripreader;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

public final class ResultStripView extends View {
    private final Paint bodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint windowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wellPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint missingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private AnalysisResult result;
    private String stripType = "dual";

    public ResultStripView(Context context, AttributeSet attrs) {
        super(context, attrs);
        bodyPaint.setColor(Color.rgb(229, 232, 230));
        bodyPaint.setShadowLayer(dp(7), 0, dp(3), Color.argb(24, 7, 58, 114));
        edgePaint.setStyle(Paint.Style.STROKE);
        edgePaint.setStrokeWidth(dp(1));
        edgePaint.setColor(Color.rgb(157, 166, 161));
        windowPaint.setColor(Color.WHITE);
        wellPaint.setColor(Color.rgb(247, 248, 247));
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeWidth(dp(4));
        missingPaint.setStrokeCap(Paint.Cap.ROUND);
        missingPaint.setStrokeWidth(dp(1));
        missingPaint.setColor(Color.rgb(224, 229, 226));
        labelPaint.setColor(Color.rgb(23, 32, 29));
        labelPaint.setFakeBoldText(true);
        labelPaint.setTextSize(dp(10));
        labelPaint.setTextAlign(Paint.Align.RIGHT);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    void setResult(AnalysisResult result) {
        this.result = result;
        stripType = result.stripType;
        invalidate();
    }

    void prepare(String stripType) {
        this.result = null;
        this.stripType = stripType;
        invalidate();
    }

    void clear() {
        result = null;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float bodyHeight = Math.min(getHeight() - dp(24), dp(212));
        float bodyWidth = Math.min(getWidth() - dp(30), bodyHeight * 0.54f);
        float left = (getWidth() - bodyWidth) / 2f;
        float top = (getHeight() - bodyHeight) / 2f;
        RectF body = new RectF(left, top, left + bodyWidth, top + bodyHeight);
        canvas.drawRoundRect(body, dp(9), dp(9), bodyPaint);
        canvas.drawRoundRect(body, dp(9), dp(9), edgePaint);

        RectF window = new RectF(
                body.left + body.width() * 0.35f,
                body.top + body.height() * 0.13f,
                body.right - body.width() * 0.12f,
                body.top + body.height() * 0.64f);
        canvas.drawRoundRect(window, dp(3), dp(3), windowPaint);
        canvas.drawRoundRect(window, dp(3), dp(3), edgePaint);

        if ("single".equals(stripType)) {
            drawLine(canvas, window, "T", 0.36f,
                    result != null && "positive".equals(result.singleResult),
                    Color.rgb(216, 92, 74));
            drawLine(canvas, window, "C", 0.68f,
                    result != null && anyControlPresent(), Color.rgb(216, 92, 74));
        } else {
            drawLine(canvas, window, "T2", 0.25f,
                    result != null && "positive".equals(result.t2Result),
                    Color.rgb(216, 92, 74));
            drawLine(canvas, window, "T1", 0.50f,
                    result != null && "positive".equals(result.t1Result),
                    Color.rgb(39, 137, 216));
            drawLine(canvas, window, "C", 0.75f,
                    result != null && anyControlPresent(), Color.rgb(216, 92, 74));
        }

        float wellWidth = body.width() * 0.28f;
        float wellHeight = body.height() * 0.20f;
        float wellLeft = body.centerX() - wellWidth / 2f;
        float wellTop = body.top + body.height() * 0.71f;
        RectF well = new RectF(wellLeft, wellTop,
                wellLeft + wellWidth, wellTop + wellHeight);
        canvas.drawRoundRect(well, wellWidth / 2f, wellWidth / 2f, wellPaint);
        canvas.drawRoundRect(well, wellWidth / 2f, wellWidth / 2f, edgePaint);

        RectF wellInner = new RectF(
                well.left + dp(4), well.top + dp(6),
                well.right - dp(4), well.bottom - dp(6));
        canvas.drawRoundRect(wellInner, wellInner.width() / 2f,
                wellInner.width() / 2f, windowPaint);
        canvas.drawRoundRect(wellInner, wellInner.width() / 2f,
                wellInner.width() / 2f, edgePaint);
    }

    private void drawLine(Canvas canvas, RectF window, String label, float yRatio,
                          boolean present, int color) {
        float y = window.top + window.height() * yRatio;
        Paint paint = present ? linePaint : missingPaint;
        if (present) paint.setColor(color);
        canvas.drawLine(window.left + dp(5), y, window.right - dp(5), y, paint);
        canvas.drawText(label, window.left - dp(6), y + dp(3.5f), labelPaint);
    }

    private boolean anyControlPresent() {
        for (AnalysisResult.StripReading strip : result.strips) {
            AnalysisResult.LineReading control = strip.lines.get("C");
            if (control != null && control.present) return true;
        }
        return false;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
