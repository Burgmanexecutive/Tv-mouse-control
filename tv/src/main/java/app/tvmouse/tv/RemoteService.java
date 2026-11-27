package app.tvmouse.tv;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Servizio di accessibilita': disegna il puntatore sopra a tutto, esegue tocchi/scorrimenti
 * con dispatchGesture e ospita il server che riceve i comandi dal telefono.
 */
public final class RemoteService extends AccessibilityService implements ConnectionServer.Handler {

    private static volatile RemoteService instance;

    static boolean isRunning() {
        return instance != null;
    }

    private static final long CLICK_MS = 60;
    private static final long LONG_PRESS_MS = 750;
    private static final long HIDE_AFTER_MS = 8000;
    private static final float MAX_STEP = 600f;

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private AudioManager audio;
    private AdbBridge adb;
    private ConnectionServer server;
    private CursorView cursor;
    private WindowManager.LayoutParams lp;
    private int pad;
    private float cx;
    private float cy;
    private final Runnable hideCursor = new Runnable() {
        @Override
        public void run() {
            if (cursor != null) cursor.setVisibility(View.INVISIBLE);
        }
    };

    // ------------------------------------------------------------ ciclo di vita

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        teardown();

        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        adb = AdbBridge.get(this);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int size = Math.round(30 * dm.density);
        pad = Math.round(3 * dm.density);
        int total = size + 2 * pad;

        cursor = new CursorView(this, size, pad);
        lp = new WindowManager.LayoutParams(
                total, total,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;

        cx = dm.widthPixels / 2f;
        cy = dm.heightPixels / 2f;
        lp.x = Math.round(cx) - pad;
        lp.y = Math.round(cy) - pad;
        cursor.setVisibility(View.INVISIBLE);
        wm.addView(cursor, lp);

        server = new ConnectionServer(this, this);
        server.start();
        instance = this;
        restoreTimer();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // non serve leggere lo schermo
    }

    @Override
    public void onInterrupt() {
        // niente da interrompere
    }

    @Override
    public boolean onUnbind(Intent intent) {
        teardown();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        teardown();
        super.onDestroy();
    }

    private void teardown() {
        instance = null;
        main.removeCallbacksAndMessages(null);
        if (server != null) {
            server.stop();
            server = null;
        }
        if (cursor != null) {
            try {
                wm.removeView(cursor);
            } catch (RuntimeException ignored) {
                // gia' rimosso
            }
            cursor = null;
        }
    }

    // ------------------------------------------------------------ comandi dal telefono

    @Override
    public void onCommand(final ConnectionServer.Session s, String[] a) {
        switch (a[0]) {
            case "MOVE": {
                if (a.length < 3) return;
                final float dx = parse(a[1]);
                final float dy = parse(a[2]);
                if (Float.isNaN(dx) || Float.isNaN(dy)) return;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        moveCursor(dx, dy);
                    }
                });
                break;
            }
            case "CLICK":
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        tap(CLICK_MS);
                    }
                });
                break;
            case "LONG":
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        tap(LONG_PRESS_MS);
                    }
                });
                break;
            case "SCROLL": {
                if (a.length < 2) return;
                final int dir = a[1].equals("-1") ? -1 : 1;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        scroll(dir);
                    }
                });
                break;
            }
            case "BACK":
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        performGlobalAction(GLOBAL_ACTION_BACK);
                    }
                });
                break;
            case "HOME":
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        performGlobalAction(GLOBAL_ACTION_HOME);
                    }
                });
                break;
            case "VOLUP":
                volume(AudioManager.ADJUST_RAISE, KeyEvent.KEYCODE_VOLUME_UP);
                break;
            case "VOLDOWN":
                volume(AudioManager.ADJUST_LOWER, KeyEvent.KEYCODE_VOLUME_DOWN);
                break;
            case "MUTE":
                volume(AudioManager.ADJUST_TOGGLE_MUTE, KeyEvent.KEYCODE_VOLUME_MUTE);
                break;
            case "CHUP":
                adbKey(s, KeyEvent.KEYCODE_CHANNEL_UP);
                break;
            case "CHDOWN":
                adbKey(s, KeyEvent.KEYCODE_CHANNEL_DOWN);
                break;
            case "POWER":
                // KEYCODE_SLEEP mette la TV in standby senza il rischio di "riaccenderla".
                // Se sulla tua TV non funziona prova KeyEvent.KEYCODE_POWER.
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        powerOff();
                    }
                });
                break;
            case "TEXT": {
                if (a.length < 2) return;
                final String text = new String(Base64.decode(a[1], Base64.NO_WRAP), StandardCharsets.UTF_8);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        editText(s, 0, text);
                    }
                });
                break;
            }
            case "DEL": {
                if (a.length < 2) return;
                final int n = parseCount(a[1]);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        editText(s, n, "");
                    }
                });
                break;
            }
            case "ENTER":
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        pressEnter(s);
                    }
                });
                break;
            case "KEY": {
                if (a.length < 2) return;
                int code;
                try {
                    code = Integer.parseInt(a[1].trim());
                } catch (NumberFormatException e) {
                    return;
                }
                if (!ALLOWED_KEYS.contains(code)) return;
                handleKey(s, code);
                break;
            }
            case "APPS":
                sendApps(s);
                break;
            case "TIMER": {
                if (a.length < 2) return;
                final int minutes = parseCount(a[1]);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        setTimer(minutes);
                    }
                });
                break;
            }
            case "LAUNCH": {
                if (a.length < 2) return;
                final String pkg = a[1];
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        launchApp(pkg);
                    }
                });
                break;
            }
            default:
                break;
        }
    }

    private static float parse(String v) {
        try {
            float f = Float.parseFloat(v);
            if (Float.isNaN(f) || Float.isInfinite(f)) return Float.NaN;
            return Math.max(-MAX_STEP, Math.min(MAX_STEP, f));
        } catch (NumberFormatException e) {
            return Float.NaN;
        }
    }

    // ------------------------------------------------------------ azioni (thread principale)

    private void moveCursor(float dx, float dy) {
        if (cursor == null) return;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float scale = dm.widthPixels / 1920f; // stessa velocita' su TV 1080p e 4K
        cx = Math.max(0f, Math.min(dm.widthPixels - 1f, cx + dx * scale));
        cy = Math.max(0f, Math.min(dm.heightPixels - 1f, cy + dy * scale));
        showCursor();
    }

    private void showCursor() {
        if (cursor == null) return;
        lp.x = Math.round(cx) - pad;
        lp.y = Math.round(cy) - pad;
        try {
            wm.updateViewLayout(cursor, lp);
        } catch (RuntimeException ignored) {
            return;
        }
        cursor.setVisibility(View.VISIBLE);
        main.removeCallbacks(hideCursor);
        main.postDelayed(hideCursor, HIDE_AFTER_MS);
    }

    private void tap(long durationMs) {
        if (cursor == null) return;
        // Prima provo a cliccare direttamente l'elemento sotto al puntatore: sulle TV il tocco
        // simulato spesso viene ignorato perché le app sono pensate per il telecomando.
        boolean longPress = durationMs >= LONG_PRESS_MS;
        AccessibilityNodeInfo target = findNodeAt(Math.round(cx), Math.round(cy),
                longPress ? MODE_LONG : MODE_CLICK);
        if (target != null && target.performAction(longPress
                ? AccessibilityNodeInfo.ACTION_LONG_CLICK : AccessibilityNodeInfo.ACTION_CLICK)) {
            showCursor();
            return;
        }
        // Ripiego: tocco simulato.
        Path p = new Path();
        p.moveTo(cx, cy);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, durationMs))
                .build();
        dispatchGesture(g, null, null);
        showCursor();
    }

    /** dir = +1: scorrimento come dito che va verso il basso, -1 verso l'alto. */
    private void scroll(int dir) {
        if (cursor == null) return;
        // Prima provo a far scorrere l'elemento scorrevole sotto al puntatore; "dito verso il basso"
        // mostra il contenuto precedente, cioè scorrimento all'indietro.
        AccessibilityNodeInfo scrollable = findNodeAt(Math.round(cx), Math.round(cy), MODE_SCROLL);
        if (scrollable != null && scrollable.performAction(dir > 0
                ? AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD : AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
            showCursor();
            return;
        }
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float maxY = dm.heightPixels - 1f;
        float dist = Math.min(dm.heightPixels * 0.35f, 420f);
        float startY = cy;
        float endY = cy + dir * dist;
        if (endY < 0f || endY > maxY) {
            startY = cy - dir * dist;
            endY = cy;
        }
        startY = Math.max(0f, Math.min(maxY, startY));
        endY = Math.max(0f, Math.min(maxY, endY));

        Path p = new Path();
        p.moveTo(cx, startY);
        p.lineTo(cx, endY);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 220))
                .build();
        dispatchGesture(g, null, null);
        showCursor();
    }

    private void volume(final int adjust, int keyCode) {
        if (adb != null && adb.isReady()) {
            adb.sendKey(keyCode, new AdbBridge.Result() {
                @Override
                public void done(boolean ok, String code) {
                    if (!ok) {
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                adjustLocal(adjust);
                            }
                        });
                    }
                }
            });
        } else {
            main.post(new Runnable() {
                @Override
                public void run() {
                    adjustLocal(adjust);
                }
            });
        }
    }

    // ------------------------------------------------------------ timer di spegnimento e spegnimento

    private static final String TIMER_PREFS = "tvmouse_timer";
    private static final int MAX_TIMER_MINUTES = 720;
    private static final Pattern POWER_TEXT = Pattern.compile(
            "(?is).*(spegni|spegnimento|power ?off|shut ?down|stand-?by|sleep|riposo).*");
    private static final Pattern CONFIRM_TEXT = Pattern.compile(
            "(?is)\\s*(ok|s[iì]|conferma|confirm|yes|spegni|power ?off)\\s*");

    private volatile ConnectionServer.Session lastSession;
    private volatile long timerEndAt; // millisecondi dell'orologio di sistema, 0 = nessun timer
    private final Runnable timerWarn = new Runnable() {
        @Override
        public void run() {
            Toast.makeText(RemoteService.this, "La TV si spegnerà tra 1 minuto", Toast.LENGTH_LONG).show();
        }
    };
    private final Runnable timerFire = new Runnable() {
        @Override
        public void run() {
            clearTimer();
            notifyTimer();
            powerOff();
        }
    };

    /** minutes = 0 annulla il timer. Il timer vive sulla TV: continua anche se il telefono si scollega. */
    private void setTimer(int minutes) {
        if (minutes <= 0) {
            clearTimer();
            notifyTimer();
            return;
        }
        timerEndAt = System.currentTimeMillis() + Math.min(minutes, MAX_TIMER_MINUTES) * 60000L;
        getSharedPreferences(TIMER_PREFS, MODE_PRIVATE).edit().putLong("end", timerEndAt).apply();
        scheduleTimer();
        notifyTimer();
    }

    private void scheduleTimer() {
        main.removeCallbacks(timerFire);
        main.removeCallbacks(timerWarn);
        long left = timerEndAt - System.currentTimeMillis();
        if (left <= 0) {
            main.post(timerFire);
            return;
        }
        main.postDelayed(timerFire, left);
        if (left > 60000L) main.postDelayed(timerWarn, left - 60000L);
    }

    private void clearTimer() {
        timerEndAt = 0;
        main.removeCallbacks(timerFire);
        main.removeCallbacks(timerWarn);
        getSharedPreferences(TIMER_PREFS, MODE_PRIVATE).edit().putLong("end", 0).apply();
    }

    /** Dopo il riavvio del servizio riprende un timer ancora valido (o scaduto da meno di 2 minuti). */
    private void restoreTimer() {
        long end = getSharedPreferences(TIMER_PREFS, MODE_PRIVATE).getLong("end", 0);
        if (end <= 0) return;
        long now = System.currentTimeMillis();
        if (end > now || now - end <= 120000L) {
            timerEndAt = end;
            scheduleTimer();
        } else {
            clearTimer();
        }
    }

    private int remainingSeconds() {
        long end = timerEndAt;
        if (end == 0) return 0;
        long left = end - System.currentTimeMillis();
        return left <= 0 ? 0 : (int) ((left + 999) / 1000);
    }

    private void notifyTimer() {
        ConnectionServer.Session s = lastSession;
        if (s != null) s.send("TIMER|" + remainingSeconds());
    }

    /** Spegne la TV (standby): via ADB se disponibile, altrimenti prova il menu di spegnimento di Android. */
    private void powerOff() {
        adb.shell("input keyevent " + KeyEvent.KEYCODE_SLEEP, new AdbBridge.Result() {
            @Override
            public void done(boolean ok, String code) {
                if (ok) return;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        powerOffWithoutAdb();
                    }
                });
            }
        });
    }

    /** Senza ADB: apre la finestra di spegnimento di sistema e clicca la voce "Spegni". Non garantito. */
    private void powerOffWithoutAdb() {
        if (!performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)) {
            reportPowerFailure();
            return;
        }
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                AccessibilityNodeInfo entry = findOnScreen(POWER_TEXT);
                if (entry == null || !entry.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    reportPowerFailure();
                    return;
                }
                // alcune TV chiedono una conferma
                main.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        AccessibilityNodeInfo confirm = findOnScreen(CONFIRM_TEXT);
                        if (confirm != null) confirm.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    }
                }, 1000);
            }
        }, 1200);
    }

    private void reportPowerFailure() {
        Toast.makeText(this, "Non sono riuscito a spegnere la TV: serve il bridge ADB.", Toast.LENGTH_LONG).show();
        ConnectionServer.Session s = lastSession;
        if (s != null) s.send("ERR|POWER");
    }

    /** Elemento cliccabile con quel testo nelle finestre sullo schermo (dalla più in alto). */
    private AccessibilityNodeInfo findOnScreen(Pattern pattern) {
        List<AccessibilityWindowInfo> windows = new ArrayList<>(getWindows());
        Collections.sort(windows, new Comparator<AccessibilityWindowInfo>() {
            @Override
            public int compare(AccessibilityWindowInfo a, AccessibilityWindowInfo b) {
                return b.getLayer() - a.getLayer();
            }
        });
        for (AccessibilityWindowInfo w : windows) {
            if (w.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue;
            AccessibilityNodeInfo found = findByText(w.getRoot(), pattern, 0);
            if (found != null) return found;
        }
        return null;
    }

    private AccessibilityNodeInfo findByText(AccessibilityNodeInfo node, Pattern pattern, int depth) {
        if (node == null || depth > 30) return null;
        CharSequence text = node.getText();
        CharSequence description = node.getContentDescription();
        boolean match = (text != null && pattern.matcher(text).matches())
                || (description != null && pattern.matcher(description).matches());
        if (match) {
            AccessibilityNodeInfo n = node;
            for (int i = 0; i < 4 && n != null; i++) {
                if (n.isClickable()) return n;
                n = n.getParent();
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findByText(node.getChild(i), pattern, depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    // ------------------------------------------------------------ elemento sotto al puntatore

    private static final int MODE_CLICK = 0;
    private static final int MODE_LONG = 1;
    private static final int MODE_SCROLL = 2;

    /** Elemento cliccabile (o scorrevole) sotto al punto, cercato nella finestra più in alto che lo contiene. */
    private AccessibilityNodeInfo findNodeAt(int x, int y, int mode) {
        List<AccessibilityWindowInfo> windows = new ArrayList<>(getWindows());
        if (windows.isEmpty()) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            return root == null ? null : hitTest(root, x, y, mode, 0);
        }
        Collections.sort(windows, new Comparator<AccessibilityWindowInfo>() {
            @Override
            public int compare(AccessibilityWindowInfo a, AccessibilityWindowInfo b) {
                return b.getLayer() - a.getLayer(); // dalla finestra più in alto
            }
        });
        Rect bounds = new Rect();
        for (AccessibilityWindowInfo w : windows) {
            if (w.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue; // il mio puntatore
            w.getBoundsInScreen(bounds);
            if (!bounds.contains(x, y)) continue;
            AccessibilityNodeInfo root = w.getRoot();
            return root == null ? null : hitTest(root, x, y, mode, 0);
        }
        return null;
    }

    /** Scende fino all'elemento più interno che contiene il punto ed è cliccabile/scorrevole. */
    private AccessibilityNodeInfo hitTest(AccessibilityNodeInfo node, int x, int y, int mode, int depth) {
        if (node == null || depth > 40) return null;
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        if (!r.contains(x, y) || !node.isVisibleToUser()) return null;
        for (int i = node.getChildCount() - 1; i >= 0; i--) {
            AccessibilityNodeInfo found = hitTest(node.getChild(i), x, y, mode, depth + 1);
            if (found != null) return found;
        }
        boolean match;
        if (mode == MODE_CLICK) {
            match = node.isClickable();
        } else if (mode == MODE_LONG) {
            match = node.isLongClickable();
        } else {
            match = node.isScrollable();
        }
        return match ? node : null;
    }

    // ------------------------------------------------------------ tasti, app, indirizzo MAC

    /** Codici tasto che il telefono può chiedere (frecce, menu, riproduzione, numeri). */
    private static final Set<Integer> ALLOWED_KEYS = new HashSet<>(Arrays.asList(
            19, 20, 21, 22, 23,                 // frecce e OK
            82, 178,                            // menu, ingressi
            85, 87, 88, 89, 90, 126, 127,       // riproduzione
            7, 8, 9, 10, 11, 12, 13, 14, 15, 16)); // 0-9
    private static final Pattern MAC = Pattern.compile("(?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}");
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z0-9._]{1,200}");

    @Override
    public void onSessionStart(final ConnectionServer.Session s) {
        lastSession = s;
        s.send("TIMER|" + remainingSeconds()); // il telefono mostra il timer eventualmente già in corso

        // Legge l'indirizzo MAC (serve al telefono per Wake-on-LAN). Funziona solo con ADB attivo.
        adb.shellOutput("cat /sys/class/net/wlan0/address 2>/dev/null; "
                + "cat /sys/class/net/eth0/address 2>/dev/null; true", new AdbBridge.Output() {
            @Override
            public void done(boolean ok, String output) {
                if (!ok) return;
                StringBuilder macs = new StringBuilder();
                Matcher m = MAC.matcher(output);
                while (m.find()) {
                    String mac = m.group().toLowerCase(Locale.ROOT);
                    if (mac.equals("00:00:00:00:00:00") || macs.indexOf(mac) >= 0) continue;
                    if (macs.length() > 0) macs.append(',');
                    macs.append(mac);
                }
                if (macs.length() > 0) s.send("MAC|" + macs);
            }
        });
    }

    /** Tasti di navigazione, riproduzione e numeri: via ADB; le frecce hanno un ripiego senza ADB. */
    private void handleKey(final ConnectionServer.Session s, final int code) {
        boolean dpad = code >= KeyEvent.KEYCODE_DPAD_UP && code <= KeyEvent.KEYCODE_DPAD_CENTER;
        if (dpad && !adb.isReady()) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    // Android 13+: tasti direzionali veri, funzionano anche in YouTube e nelle app per TV.
                    if (Build.VERSION.SDK_INT >= 33 && dpadGlobal(code)) return;
                    focusMove(code);
                }
            });
            return;
        }
        adbKey(s, code);
    }

    /** Frecce/OK tramite azioni globali di accessibilità (API 33+). */
    private boolean dpadGlobal(int code) {
        if (Build.VERSION.SDK_INT < 33) return false;
        int action;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: action = GLOBAL_ACTION_DPAD_UP; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: action = GLOBAL_ACTION_DPAD_DOWN; break;
            case KeyEvent.KEYCODE_DPAD_LEFT: action = GLOBAL_ACTION_DPAD_LEFT; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: action = GLOBAL_ACTION_DPAD_RIGHT; break;
            case KeyEvent.KEYCODE_DPAD_CENTER: action = GLOBAL_ACTION_DPAD_CENTER; break;
            default: return false;
        }
        return performGlobalAction(action);
    }

    /** Senza ADB: sposta il focus di sistema (o clicca l'elemento selezionato). Non funziona in tutte le app. */
    private void focusMove(int code) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        AccessibilityNodeInfo cur = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (cur == null) cur = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
        if (cur == null) return;
        if (code == KeyEvent.KEYCODE_DPAD_CENTER) {
            cur.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            return;
        }
        int dir;
        if (code == KeyEvent.KEYCODE_DPAD_UP) {
            dir = View.FOCUS_UP;
        } else if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
            dir = View.FOCUS_DOWN;
        } else if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
            dir = View.FOCUS_LEFT;
        } else {
            dir = View.FOCUS_RIGHT;
        }
        AccessibilityNodeInfo next = cur.focusSearch(dir);
        if (next != null) next.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
    }

    /** Invia al telefono l'elenco delle app avviabili: APP|pacchetto|nome(base64) ... APPSEND. */
    private void sendApps(ConnectionServer.Session s) {
        PackageManager pm = getPackageManager();
        Map<String, String> byPackage = new HashMap<>();
        String[] categories = {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER};
        for (String category : categories) {
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || byPackage.containsKey(pkg)) continue;
                CharSequence label = ri.loadLabel(pm);
                byPackage.put(pkg, label == null ? pkg : label.toString());
            }
        }
        List<Map.Entry<String, String>> items = new ArrayList<>(byPackage.entrySet());
        Collections.sort(items, new Comparator<Map.Entry<String, String>>() {
            @Override
            public int compare(Map.Entry<String, String> x, Map.Entry<String, String> y) {
                return x.getValue().compareToIgnoreCase(y.getValue());
            }
        });
        int n = 0;
        for (Map.Entry<String, String> e : items) {
            if (n++ >= 150) break;
            String label = e.getValue();
            if (label.length() > 40) label = label.substring(0, 40);
            s.send("APP|" + e.getKey() + "|"
                    + Base64.encodeToString(label.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
        }
        s.send("APPSEND");
    }

    /** Il pacchetto viene solo cercato tra le app installate: nel comando va il componente trovato da Android. */
    private void launchApp(String pkg) {
        if (!PACKAGE.matcher(pkg).matches()) return;
        PackageManager pm = getPackageManager();
        Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
        if (launch == null) return;
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        final Intent intent = launch;
        ComponentName cn = launch.getComponent();
        if (adb.isReady() && cn != null) {
            adb.shell("am start -n '" + cn.flattenToShortString() + "'", new AdbBridge.Result() {
                @Override
                public void done(boolean ok, String code) {
                    if (ok) return;
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            startFromService(intent);
                        }
                    });
                }
            });
        } else {
            startFromService(intent);
        }
    }

    private void startFromService(Intent intent) {
        try {
            startActivity(intent);
        } catch (RuntimeException ignored) {
            // Android può impedire l'avvio da un servizio in secondo piano
        }
    }

    // ------------------------------------------------------------ testo dalla tastiera del telefono

    private static int parseCount(String v) {
        try {
            return Math.max(0, Math.min(5000, Integer.parseInt(v.trim())));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Campo di testo con il focus di input, anche se la tastiera della TV occupa la finestra attiva. */
    private AccessibilityNodeInfo findInputNode() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            AccessibilityNodeInfo f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (f != null && f.isEditable()) return f;
        }
        List<AccessibilityWindowInfo> windows = getWindows();
        for (AccessibilityWindowInfo w : windows) {
            AccessibilityNodeInfo r = w.getRoot();
            if (r == null) continue;
            AccessibilityNodeInfo f = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (f != null && f.isEditable()) return f;
        }
        return null;
    }

    /** Cancella deleteCount caratteri prima del cursore, poi inserisce insert: nel campo selezionato sulla TV. */
    private void editText(ConnectionServer.Session s, int deleteCount, String insert) {
        if (deleteCount == 0 && insert.isEmpty()) return;
        final int originalDelete = deleteCount;

        AccessibilityNodeInfo f = findInputNode();
        if (f == null || f.isPassword()) {
            // campo non visibile all'accessibilità, oppure password (il cui contenuto non viene letto)
            adbType(s, originalDelete, insert);
            return;
        }

        CharSequence cur = f.getText();
        if (Build.VERSION.SDK_INT >= 26 && f.isShowingHintText()) cur = null;
        StringBuilder sb = new StringBuilder(cur == null ? "" : cur.toString());
        int len = sb.length();
        int start = f.getTextSelectionStart();
        int end = f.getTextSelectionEnd();
        if (start < 0 || end < 0) {
            start = len;
            end = len;
        }
        start = Math.max(0, Math.min(len, start));
        end = Math.max(0, Math.min(len, end));
        if (start > end) {
            int t = start;
            start = end;
            end = t;
        }

        if (start != end) {
            // c'è una selezione: viene sostituita (un backspace conta come cancellazione della selezione)
            sb.delete(start, end);
            if (deleteCount > 0) deleteCount--;
        }
        if (deleteCount > 0 && start > 0) {
            int n = Math.min(deleteCount, sb.codePointCount(0, start));
            int from = sb.offsetByCodePoints(start, -n);
            sb.delete(from, start);
            start = from;
        }
        sb.insert(start, insert);
        int caret = start + insert.length();

        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, sb.toString());
        if (!f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            adbType(s, originalDelete, insert);
            return;
        }
        Bundle sel = new Bundle();
        sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, caret);
        sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, caret);
        f.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel);
    }

    /**
     * Ripiego via ADB ("input text"): solo caratteri ASCII stampabili. Escludo apice, percentuale
     * e backslash, così il testo resta sempre racchiuso tra apici semplici senza rischi.
     */
    private void adbType(final ConnectionServer.Session s, int deleteCount, String insert) {
        StringBuilder cmd = new StringBuilder();
        int dels = Math.min(deleteCount, 30);
        for (int i = 0; i < dels; i++) {
            if (cmd.length() > 0) cmd.append("; ");
            cmd.append("input keyevent 67");
        }
        StringBuilder t = new StringBuilder();
        boolean skipped = false;
        for (int i = 0; i < insert.length(); i++) {
            char c = insert.charAt(i);
            if (c == ' ') {
                t.append("%s");
            } else if (c > ' ' && c < 0x7F && c != '\'' && c != '%' && c != '\\') {
                t.append(c);
            } else {
                skipped = true;
            }
        }
        if (t.length() > 0) {
            if (cmd.length() > 0) cmd.append("; ");
            cmd.append("input text '").append(t).append('\'');
        }
        if (skipped) s.send("ERR|CHARS");
        if (cmd.length() == 0) return;
        adb.shell(cmd.toString(), new AdbBridge.Result() {
            @Override
            public void done(boolean ok, String code) {
                if (!ok) s.send("ERR|NOFIELD");
            }
        });
    }

    private void pressEnter(final ConnectionServer.Session s) {
        AccessibilityNodeInfo f = findInputNode();
        if (f != null && Build.VERSION.SDK_INT >= 30
                && f.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId())) {
            return;
        }
        adb.sendKey(KeyEvent.KEYCODE_ENTER, new AdbBridge.Result() {
            @Override
            public void done(boolean ok, String code) {
                if (!ok) s.send("ERR|NOFIELD");
            }
        });
    }

    private void adjustLocal(int adjust) {
        if (audio == null) return;
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, adjust, AudioManager.FLAG_SHOW_UI);
    }

    private void adbKey(final ConnectionServer.Session s, int keyCode) {
        adb.sendKey(keyCode, new AdbBridge.Result() {
            @Override
            public void done(boolean ok, String code) {
                if (!ok) s.send("ERR|" + code);
            }
        });
    }
}
