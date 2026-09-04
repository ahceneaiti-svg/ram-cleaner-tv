package com.aia.ramcleaner;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * Jauge circulaire facon "capture.png" : un arc de 270 degres ouvert vers le bas,
 * une piste sombre, un arc de progression colore a bouts arrondis, et la valeur
 * affichee au centre.
 */
public class GaugeView extends View {

    private static final float START_ANGLE = 135f;
    private static final float SWEEP_MAX = 270f;

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint value = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint caption = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();

    private float progress = 0f;          // 0..1 effectivement dessine
    private float target = 0f;            // 0..1 vise
    private int arcColor = 0xFF5DD39E;
    private String centerText = "";
    private String subText = "";
    private float stroke;
    private ValueAnimator animator;

    public GaugeView(Context c) {
        super(c);
        init();
    }

    public GaugeView(Context c, AttributeSet a) {
        super(c, a);
        init();
    }

    public GaugeView(Context c, AttributeSet a, int s) {
        super(c, a, s);
        init();
    }

    private void init() {
        stroke = dp(13f);

        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(stroke);
        track.setStrokeCap(Paint.Cap.ROUND);
        track.setColor(0xFF242D37);

        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeWidth(stroke);
        arc.setStrokeCap(Paint.Cap.ROUND);
        arc.setColor(arcColor);

        value.setColor(arcColor);
        value.setTextAlign(Paint.Align.CENTER);
        value.setFakeBoldText(true);
        value.setTextSize(sp(20f));

        caption.setColor(0xFF93A1AD);
        caption.setTextAlign(Paint.Align.CENTER);
        caption.setTextSize(sp(11f));
    }

    public void setArcColor(int color) {
        arcColor = color;
        arc.setColor(color);
        value.setColor(color);
        invalidate();
    }

    public void setCenterText(String s) {
        centerText = s == null ? "" : s;
        invalidate();
    }

    public void setSubText(String s) {
        subText = s == null ? "" : s;
        invalidate();
    }

    /** Fixe la progression immediatement (0..1). */
    public void setProgress(float p) {
        cancelAnim();
        progress = target = clamp(p);
        invalidate();
    }

    /** Anime de la valeur courante vers p (0..1). */
    public void setProgressAnimated(float p) {
        p = clamp(p);
        if (Math.abs(p - target) < 0.001f) return;
        cancelAnim();
        target = p;
        animator = ValueAnimator.ofFloat(progress, target);
        animator.setDuration(650);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                progress = (float) a.getAnimatedValue();
                invalidate();
            }
        });
        animator.start();
    }

    private void cancelAnim() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelAnim();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int def = (int) dp(150f);
        int w = resolveSize(def, wSpec);
        int h = resolveSize(def, hSpec);
        int size = Math.min(w, h);
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float pad = stroke / 2f + dp(4f);
        box.set(pad, pad, getWidth() - pad, getHeight() - pad);

        canvas.drawArc(box, START_ANGLE, SWEEP_MAX, false, track);
        float sweep = SWEEP_MAX * progress;
        if (sweep > 0.5f) {
            canvas.drawArc(box, START_ANGLE, sweep, false, arc);
        }

        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;

        if (!centerText.isEmpty()) {
            value.setTextSize(getWidth() * 0.17f);
            Paint.FontMetrics fm = value.getFontMetrics();
            float baseline = cy - (fm.ascent + fm.descent) / 2f;
            if (!subText.isEmpty()) baseline -= dp(8f);
            canvas.drawText(centerText, cx, baseline, value);
        }
        if (!subText.isEmpty()) {
            caption.setTextSize(getWidth() * 0.085f);
            canvas.drawText(subText, cx, cy + dp(16f), caption);
        }
    }

    private static float clamp(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private float dp(float v) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private float sp(float v) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, v, getResources().getDisplayMetrics());
    }
}
