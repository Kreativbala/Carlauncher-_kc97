package com.kc97.carlauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

public class CarStatusView extends View {
    private boolean headlightsOn = false;
    private boolean handbrakeOn = false;
    private boolean reverseOn = false;
    private int speedKmh = 0;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint beamPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmpRect = new RectF();
    private final Path tmpPath = new Path();

    public CarStatusView(Context context) {
        super(context);
        strokePaint.setStyle(Paint.Style.STROKE);
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    public void updateState(boolean headlights, boolean handbrake, boolean reverse, int speed) {
        if (headlightsOn == headlights && handbrakeOn == handbrake && reverseOn == reverse && speedKmh == speed) {
            return;
        }
        headlightsOn = headlights;
        handbrakeOn = handbrake;
        reverseOn = reverse;
        speedKmh = speed;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;

        float cx = w * 0.5f;
        float carH = Math.min(h * 0.64f, w * 0.85f);
        float carW = carH * 0.48f;
        float top = h * 0.54f - carH * 0.5f;
        float bottom = top + carH;
        float left = cx - carW * 0.5f;
        float right = cx + carW * 0.5f;

        // Subtle road lanes beside the car
        strokePaint.setStrokeWidth(2.5f);
        int laneAlpha = speedKmh > 0 ? Math.min(180, 60 + speedKmh * 2) : 38;
        strokePaint.setColor(Color.argb(laneAlpha, 0, 212, 255));
        float laneOffset = carW * 0.92f;
        canvas.drawLine(cx - laneOffset, h * 0.12f, cx - laneOffset * 1.18f, h * 0.95f, strokePaint);
        canvas.drawLine(cx + laneOffset, h * 0.12f, cx + laneOffset * 1.18f, h * 0.95f, strokePaint);

        // Headlight beams when headlights are ON
        if (headlightsOn) {
            float hlY = top + carH * 0.06f;
            float leftHlX = left + carW * 0.20f;
            float rightHlX = right - carW * 0.20f;
            float beamTop = Math.max(0f, top - carH * 0.55f);

            beamPaint.setShader(new LinearGradient(
                    cx, hlY, cx, beamTop,
                    new int[]{Color.argb(185, 190, 245, 255), Color.argb(70, 0, 212, 255), Color.argb(0, 0, 212, 255)},
                    new float[]{0f, 0.45f, 1f},
                    Shader.TileMode.CLAMP
            ));

            // Left cone
            tmpPath.reset();
            tmpPath.moveTo(leftHlX - carW * 0.08f, hlY);
            tmpPath.lineTo(leftHlX + carW * 0.09f, hlY);
            tmpPath.lineTo(leftHlX + carW * 0.28f, beamTop);
            tmpPath.lineTo(leftHlX - carW * 0.58f, beamTop);
            tmpPath.close();
            canvas.drawPath(tmpPath, beamPaint);

            // Right cone
            tmpPath.reset();
            tmpPath.moveTo(rightHlX - carW * 0.09f, hlY);
            tmpPath.lineTo(rightHlX + carW * 0.08f, hlY);
            tmpPath.lineTo(rightHlX + carW * 0.58f, beamTop);
            tmpPath.lineTo(rightHlX - carW * 0.28f, beamTop);
            tmpPath.close();
            canvas.drawPath(tmpPath, beamPaint);
        }

        // Ambient under-car shadow/glow
        glowPaint.setShader(new RadialGradient(
                cx, (top + bottom) * 0.5f, carH * 0.62f,
                headlightsOn ? Color.argb(55, 0, 212, 255) : Color.argb(80, 0, 0, 0),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
        ));
        tmpRect.set(left - carW * 0.28f, top - carH * 0.08f, right + carW * 0.28f, bottom + carH * 0.12f);
        canvas.drawOval(tmpRect, glowPaint);

        // Side mirrors
        paint.setShader(null);
        paint.setColor(Color.parseColor("#3B4A62"));
        float mirrorY = top + carH * 0.30f;
        float mirrorW = carW * 0.13f;
        float mirrorH = carH * 0.055f;
        tmpRect.set(left - mirrorW * 0.75f, mirrorY, left + mirrorW * 0.25f, mirrorY + mirrorH);
        canvas.drawRoundRect(tmpRect, 6f, 6f, paint);
        tmpRect.set(right - mirrorW * 0.25f, mirrorY, right + mirrorW * 0.75f, mirrorY + mirrorH);
        canvas.drawRoundRect(tmpRect, 6f, 6f, paint);

        // Main car body with metallic gradient
        paint.setShader(new LinearGradient(
                left, top, right, bottom,
                new int[]{Color.parseColor("#4B5B73"), Color.parseColor("#2A3547"), Color.parseColor("#1D2533")},
                new float[]{0f, 0.5f, 1f},
                Shader.TileMode.CLAMP
        ));
        tmpRect.set(left, top, right, bottom);
        canvas.drawRoundRect(tmpRect, carW * 0.30f, carW * 0.34f, paint);

        // Body contour outline
        strokePaint.setStrokeWidth(2.4f);
        strokePaint.setColor(headlightsOn ? Color.parseColor("#6EE7FF") : Color.parseColor("#546885"));
        canvas.drawRoundRect(tmpRect, carW * 0.30f, carW * 0.34f, strokePaint);

        // Hood crease lines
        strokePaint.setStrokeWidth(1.6f);
        strokePaint.setColor(Color.argb(90, 200, 225, 255));
        canvas.drawLine(left + carW * 0.24f, top + carH * 0.09f, left + carW * 0.28f, top + carH * 0.27f, strokePaint);
        canvas.drawLine(right - carW * 0.24f, top + carH * 0.09f, right - carW * 0.28f, top + carH * 0.27f, strokePaint);

        // Cabin / glass canopy
        paint.setShader(new LinearGradient(
                cx, top + carH * 0.24f, cx, bottom - carH * 0.16f,
                new int[]{Color.parseColor("#0B111C"), Color.parseColor("#182436"), Color.parseColor("#0B111C")},
                null,
                Shader.TileMode.CLAMP
        ));
        tmpRect.set(left + carW * 0.14f, top + carH * 0.25f, right - carW * 0.14f, bottom - carH * 0.17f);
        canvas.drawRoundRect(tmpRect, carW * 0.18f, carW * 0.20f, paint);

        // Roof panel inside cabin
        paint.setShader(null);
        paint.setColor(Color.parseColor("#253143"));
        tmpRect.set(left + carW * 0.21f, top + carH * 0.42f, right - carW * 0.21f, bottom - carH * 0.31f);
        canvas.drawRoundRect(tmpRect, carW * 0.10f, carW * 0.10f, paint);

        // Front LED headlights strips
        paint.setColor(headlightsOn ? Color.parseColor("#E0FCFF") : Color.parseColor("#6E829E"));
        tmpRect.set(left + carW * 0.12f, top + carH * 0.03f, left + carW * 0.35f, top + carH * 0.075f);
        canvas.drawRoundRect(tmpRect, 6f, 6f, paint);
        tmpRect.set(right - carW * 0.35f, top + carH * 0.03f, right - carW * 0.12f, top + carH * 0.075f);
        canvas.drawRoundRect(tmpRect, 6f, 6f, paint);

        // Rear LED taillight / brake bar
        boolean rearBright = headlightsOn || handbrakeOn;
        if (rearBright) {
            glowPaint.setShader(new RadialGradient(
                    cx, bottom - carH * 0.02f, carW * 0.55f,
                    Color.argb(handbrakeOn ? 180 : 105, 255, 36, 66),
                    Color.TRANSPARENT,
                    Shader.TileMode.CLAMP
            ));
            tmpRect.set(left - carW * 0.15f, bottom - carH * 0.08f, right + carW * 0.15f, bottom + carH * 0.16f);
            canvas.drawOval(tmpRect, glowPaint);
        }
        paint.setColor(handbrakeOn ? Color.parseColor("#FF2A44") : (headlightsOn ? Color.parseColor("#E11D48") : Color.parseColor("#5C2530")));
        tmpRect.set(left + carW * 0.13f, bottom - carH * 0.065f, right - carW * 0.13f, bottom - carH * 0.028f);
        canvas.drawRoundRect(tmpRect, 6f, 6f, paint);

        // Reverse white lights if reverse gear active
        if (reverseOn) {
            paint.setColor(Color.WHITE);
            tmpRect.set(left + carW * 0.28f, bottom - carH * 0.055f, left + carW * 0.42f, bottom - carH * 0.03f);
            canvas.drawRoundRect(tmpRect, 4f, 4f, paint);
            tmpRect.set(right - carW * 0.42f, bottom - carH * 0.055f, right - carW * 0.28f, bottom - carH * 0.03f);
            canvas.drawRoundRect(tmpRect, 4f, 4f, paint);
        }
    }
}
