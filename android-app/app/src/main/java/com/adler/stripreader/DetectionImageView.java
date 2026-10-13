package com.adler.stripreader;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public final class DetectionImageView extends View {
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<float[][]> cornerSets = new ArrayList<>();
    private Bitmap bitmap;
    private int coordinateWidth;
    private int coordinateHeight;

    public DetectionImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(Color.argb(40, 53, 216, 115));
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(dp(2.5f));
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        strokePaint.setColor(Color.rgb(21, 122, 69));
        markerPaint.setStyle(Paint.Style.FILL);
        markerPaint.setColor(Color.WHITE);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    void setBitmap(Bitmap bitmap, int coordinateWidth, int coordinateHeight) {
        this.bitmap = bitmap;
        this.coordinateWidth = coordinateWidth;
        this.coordinateHeight = coordinateHeight;
        invalidate();
    }

    void setCornerSets(List<float[][]> corners) {
        cornerSets.clear();
        if (corners != null) cornerSets.addAll(corners);
        invalidate();
    }

    void clear() {
        bitmap = null;
        coordinateWidth = 0;
        coordinateHeight = 0;
        cornerSets.clear();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.rgb(237, 241, 238));
        if (bitmap == null || bitmap.isRecycled()) return;

        float inset = dp(10);
        float availableWidth = getWidth() - inset * 2;
        float availableHeight = getHeight() - inset * 2;
        float scale = Math.min(availableWidth / bitmap.getWidth(), availableHeight / bitmap.getHeight());
        float drawWidth = bitmap.getWidth() * scale;
        float drawHeight = bitmap.getHeight() * scale;
        float left = (getWidth() - drawWidth) / 2f;
        float top = (getHeight() - drawHeight) / 2f;
        RectF destination = new RectF(left, top, left + drawWidth, top + drawHeight);
        float overlayScaleX = drawWidth / Math.max(coordinateWidth, 1);
        float overlayScaleY = drawHeight / Math.max(coordinateHeight, 1);

        canvas.save();
        Path imageClip = new Path();
        imageClip.addRoundRect(destination, dp(5), dp(5), Path.Direction.CW);
        canvas.clipPath(imageClip);
        canvas.drawBitmap(bitmap, null, destination, bitmapPaint);
        canvas.restore();

        for (float[][] corners : cornerSets) {
            if (corners == null || corners.length != 4) continue;
            Path path = new Path();
            path.moveTo(left + corners[0][0] * overlayScaleX, top + corners[0][1] * overlayScaleY);
            for (int i = 1; i < 4; i++) {
                path.lineTo(left + corners[i][0] * overlayScaleX, top + corners[i][1] * overlayScaleY);
            }
            path.close();
            canvas.drawPath(path, fillPaint);
            canvas.drawPath(path, strokePaint);
            float markerX = left + corners[0][0] * overlayScaleX;
            float markerY = top + corners[0][1] * overlayScaleY;
            canvas.drawCircle(markerX, markerY, dp(5), markerPaint);
            canvas.drawCircle(markerX, markerY, dp(5), strokePaint);
        }
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
