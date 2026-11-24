package it.gabriele.mazdalauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/** Indicatore circolare semplice: anello grigio, arco rosso, valore e unità al centro. */
public class GaugeView extends View {
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint unitPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private String value = "—";
    private String unit = "";
    private float fraction = 0f;

    public GaugeView(Context c, AttributeSet a) {
        super(c, a);
        float d = c.getResources().getDisplayMetrics().density;
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(6 * d);
        ring.setColor(0x40FFFFFF);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeWidth(6 * d);
        arc.setStrokeCap(Paint.Cap.ROUND);
        arc.setColor(0xFFB3112E);
        valuePaint.setColor(0xFFECEFF1);
        valuePaint.setTextAlign(Paint.Align.CENTER);
        valuePaint.setTextSize(28 * d);
        unitPaint.setColor(0xFF9AA0A6);
        unitPaint.setTextAlign(Paint.Align.CENTER);
        unitPaint.setTextSize(13 * d);
    }

    public void set(String value, String unit, float fraction) {
        this.value = value;
        this.unit = unit;
        this.fraction = Math.max(0f, Math.min(1f, fraction));
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        int size = MeasureSpec.getSize(w);
        int max = Math.round(150 * getResources().getDisplayMetrics().density);
        size = Math.min(size, max);
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onDraw(Canvas c) {
        float pad = ring.getStrokeWidth();
        box.set(pad, pad, getWidth() - pad, getHeight() - pad);
        c.drawArc(box, 135, 270, false, ring);
        if (fraction > 0f) c.drawArc(box, 135, 270 * fraction, false, arc);
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        c.drawText(value, cx, cy + valuePaint.getTextSize() * 0.15f, valuePaint);
        c.drawText(unit, cx, cy + valuePaint.getTextSize() * 0.15f + unitPaint.getTextSize() * 1.4f, unitPaint);
    }
}
