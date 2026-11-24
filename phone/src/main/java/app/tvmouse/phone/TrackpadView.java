package app.tvmouse.phone;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * Trackpad con due modi:
 *  - puntatore: un dito muove il cursore, tocco rapido = clic, tocco lungo = clic lungo,
 *    due dita in verticale = scorrimento
 *  - navigazione: scorrere il dito equivale a premere le frecce, tocco rapido = OK
 */
final class TrackpadView extends View {

    static final int DIR_UP = 0;
    static final int DIR_DOWN = 1;
    static final int DIR_LEFT = 2;
    static final int DIR_RIGHT = 3;

    interface Listener {
        void onMove(float dxTv, float dyTv);

        void onClick();

        void onLongPress();

        void onScroll(int dir);

        /** Solo in modo navigazione: una freccia (DIR_UP, DIR_DOWN, DIR_LEFT, DIR_RIGHT). */
        default void onNavigate(int dir) {
        }
    }

    /** Pixel TV per ogni dp percorso con il dito. */
    private static final float SENSITIVITY = 2.4f;
    private static final long LONG_PRESS_MS = 550;
    private static final long TAP_MAX_MS = 350;

    private Listener listener;
    private final float density;
    private final int slop;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint title = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Runnable longPress;

    private boolean navigation;
    private float sensitivityScale = 1f;
    private boolean haptics = true;

    private float lastX;
    private float lastY;
    private float downX;
    private float downY;
    private long downTime;
    private boolean moved;
    private boolean longFired;
    private boolean scrolling;
    private float scrollAccum;
    private float lastScrollY;
    private long lastScrollTime;
    private float navX;
    private float navY;

    TrackpadView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        slop = ViewConfiguration.get(context).getScaledTouchSlop();

        fill.setColor(0xFF1E1E26);
        fill.setStyle(Paint.Style.FILL);
        border.setColor(0xFF3C3C4A);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(2f * density);
        title.setColor(0xFF8A8A9A);
        title.setTextAlign(Paint.Align.CENTER);
        title.setTextSize(22f * density);
        hint.setColor(0xFF6A6A7A);
        hint.setTextAlign(Paint.Align.CENTER);
        hint.setTextSize(13f * density);

        longPress = new Runnable() {
            @Override
            public void run() {
                if (!moved && !scrolling) {
                    longFired = true;
                    haptic(HapticFeedbackConstants.LONG_PRESS);
                    if (listener != null) listener.onLongPress();
                }
            }
        };
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setNavigation(boolean on) {
        navigation = on;
        invalidate();
    }

    /** Moltiplicatore della velocita' del puntatore (1 = normale). */
    void setSensitivityScale(float scale) {
        sensitivityScale = scale;
    }

    void setHaptics(boolean on) {
        haptics = on;
    }

    private void haptic(int type) {
        if (haptics) performHapticFeedback(type);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float r = 20f * density;
        float inset = border.getStrokeWidth() / 2f;
        rect.set(inset, inset, getWidth() - inset, getHeight() - inset);
        canvas.drawRoundRect(rect, r, r, fill);
        canvas.drawRoundRect(rect, r, r, border);

        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        if (navigation) {
            canvas.drawText("Navigazione", cx, cy - 8f * density, title);
            canvas.drawText("scorri il dito: frecce - tocca: OK", cx, cy + 20f * density, hint);
        } else {
            canvas.drawText("Trackpad", cx, cy - 8f * density, title);
            canvas.drawText("1 dito: muovi - tocca: clic - tieni premuto: clic lungo", cx, cy + 20f * density, hint);
            canvas.drawText("2 dita su/giu': scorri", cx, cy + 40f * density, hint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                downX = e.getX();
                downY = e.getY();
                lastX = downX;
                lastY = downY;
                downTime = e.getEventTime();
                moved = false;
                longFired = false;
                scrolling = false;
                scrollAccum = 0f;
                navX = 0f;
                navY = 0f;
                handler.postDelayed(longPress, LONG_PRESS_MS);
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                scrolling = true;
                handler.removeCallbacks(longPress);
                scrollAccum = 0f;
                lastScrollY = averageY(e, -1);
                return true;

            case MotionEvent.ACTION_POINTER_UP:
                lastScrollY = averageY(e, e.getActionIndex());
                return true;

            case MotionEvent.ACTION_MOVE:
                if (scrolling) {
                    handleScroll(e);
                } else if (e.getPointerCount() == 1) {
                    handleMove(e);
                }
                return true;

            case MotionEvent.ACTION_UP:
                handler.removeCallbacks(longPress);
                if (!moved && !scrolling && !longFired && e.getEventTime() - downTime < TAP_MAX_MS) {
                    haptic(HapticFeedbackConstants.KEYBOARD_TAP);
                    if (listener != null) listener.onClick();
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(longPress);
                return true;

            default:
                return super.onTouchEvent(e);
        }
    }

    private void handleMove(MotionEvent e) {
        float x = e.getX();
        float y = e.getY();
        if (!moved && Math.hypot(x - downX, y - downY) > slop) {
            moved = true;
            handler.removeCallbacks(longPress);
        }
        if (moved && !longFired && listener != null) {
            if (navigation) {
                navX += x - lastX;
                navY += y - lastY;
                float step = 48f * density;
                if (Math.abs(navX) >= step || Math.abs(navY) >= step) {
                    int dir;
                    if (Math.abs(navX) > Math.abs(navY)) {
                        dir = navX > 0 ? DIR_RIGHT : DIR_LEFT;
                    } else {
                        dir = navY > 0 ? DIR_DOWN : DIR_UP;
                    }
                    navX = 0f;
                    navY = 0f;
                    haptic(HapticFeedbackConstants.KEYBOARD_TAP);
                    listener.onNavigate(dir);
                }
            } else {
                float dxDp = (x - lastX) / density;
                float dyDp = (y - lastY) / density;
                float speed = (float) Math.hypot(dxDp, dyDp);
                float gain = SENSITIVITY * sensitivityScale * (1f + Math.min(speed / 12f, 1.5f)); // accelerazione
                listener.onMove(dxDp * gain, dyDp * gain);
            }
        }
        lastX = x;
        lastY = y;
    }

    private void handleScroll(MotionEvent e) {
        if (navigation) return;
        float y = averageY(e, -1);
        scrollAccum += y - lastScrollY;
        lastScrollY = y;
        float step = 28f * density;
        long now = e.getEventTime();
        if (Math.abs(scrollAccum) >= step && now - lastScrollTime >= 140) {
            int dir = scrollAccum > 0 ? 1 : -1;
            scrollAccum = 0f;
            lastScrollTime = now;
            if (listener != null) listener.onScroll(dir);
        }
    }

    /** Media delle Y dei puntatori attivi, escludendo quello con indice skip (-1 = nessuno). */
    private static float averageY(MotionEvent e, int skip) {
        float sum = 0f;
        int n = 0;
        for (int i = 0; i < e.getPointerCount(); i++) {
            if (i == skip) continue;
            sum += e.getY(i);
            n++;
        }
        return n == 0 ? 0f : sum / n;
    }
}
