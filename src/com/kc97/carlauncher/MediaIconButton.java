package com.kc97.carlauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;

public class MediaIconButton extends View {
    public static final int ICON_PREV = 0;
    public static final int ICON_PLAY = 1;
    public static final int ICON_PAUSE = 2;
    public static final int ICON_NEXT = 3;

    private int iconType;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();

    public MediaIconButton(Context context, int iconType, String bgHex, String strokeHex, String fgHex) {
        super(context);
        this.iconType = iconType;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.parseColor(fgHex));
        setColors(bgHex, strokeHex, fgHex);
        setClickable(true);
        setFocusable(true);
    }

    public void setIconType(int type) {
        if (iconType != type) {
            iconType = type;
            invalidate();
        }
    }

    public void setColors(String bgHex, String strokeHex, String fgHex) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor(bgHex));
        int r = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 14, getResources().getDisplayMetrics()));
        int st = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1, getResources().getDisplayMetrics()));
        gd.setCornerRadius(r);
        gd.setStroke(st, Color.parseColor(strokeHex));
        setBackground(gd);
        paint.setColor(Color.parseColor(fgHex));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;

        float cx = w * 0.5f;
        float cy = h * 0.5f;
        float s = Math.min(w, h) * 0.24f;

        path.reset();
        switch (iconType) {
            case ICON_PLAY: {
                float ox = s * 0.14f;
                path.moveTo(cx - s * 0.72f + ox, cy - s * 0.95f);
                path.lineTo(cx + s * 0.95f + ox, cy);
                path.lineTo(cx - s * 0.72f + ox, cy + s * 0.95f);
                path.close();
                canvas.drawPath(path, paint);
                break;
            }
            case ICON_PAUSE: {
                float barW = s * 0.44f;
                float gap = s * 0.34f;
                float barH = s * 0.92f;
                rect.set(cx - gap * 0.5f - barW, cy - barH, cx - gap * 0.5f, cy + barH);
                canvas.drawRoundRect(rect, barW * 0.35f, barW * 0.35f, paint);
                rect.set(cx + gap * 0.5f, cy - barH, cx + gap * 0.5f + barW, cy + barH);
                canvas.drawRoundRect(rect, barW * 0.35f, barW * 0.35f, paint);
                break;
            }
            case ICON_PREV: {
                float barW = s * 0.28f;
                float barH = s * 0.82f;
                float leftX = cx - s * 0.88f;
                rect.set(leftX, cy - barH, leftX + barW, cy + barH);
                canvas.drawRoundRect(rect, barW * 0.4f, barW * 0.4f, paint);

                path.moveTo(cx + s * 0.88f, cy - barH);
                path.lineTo(leftX + barW + s * 0.10f, cy);
                path.lineTo(cx + s * 0.88f, cy + barH);
                path.close();
                canvas.drawPath(path, paint);
                break;
            }
            case ICON_NEXT: {
                float barW = s * 0.28f;
                float barH = s * 0.82f;
                float rightX = cx + s * 0.88f;
                rect.set(rightX - barW, cy - barH, rightX, cy + barH);
                canvas.drawRoundRect(rect, barW * 0.4f, barW * 0.4f, paint);

                path.moveTo(cx - s * 0.88f, cy - barH);
                path.lineTo(rightX - barW - s * 0.10f, cy);
                path.lineTo(cx - s * 0.88f, cy + barH);
                path.close();
                canvas.drawPath(path, paint);
                break;
            }
        }
    }
}
