package app.tvmouse.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Freccia del puntatore. La punta e' nell'angolo in alto a sinistra (piu' il margine). */
final class CursorView extends View {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrow = new Path();
    private final float pad;

    CursorView(Context context, float size, float pad) {
        super(context);
        this.pad = pad;

        fill.setColor(0xFFFFFFFF);
        fill.setStyle(Paint.Style.FILL);

        edge.setColor(0xFF000000);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(Math.max(2f, size / 12f));
        edge.setStrokeJoin(Paint.Join.ROUND);

        float s = size;
        arrow.moveTo(0f, 0f);
        arrow.lineTo(0f, 0.85f * s);
        arrow.lineTo(0.22f * s, 0.65f * s);
        arrow.lineTo(0.38f * s, 1.00f * s);
        arrow.lineTo(0.52f * s, 0.94f * s);
        arrow.lineTo(0.36f * s, 0.60f * s);
        arrow.lineTo(0.65f * s, 0.60f * s);
        arrow.close();

        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.translate(pad, pad);
        canvas.drawPath(arrow, fill);
        canvas.drawPath(arrow, edge);
    }
}
