package app.tvmouse.phone;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.TextUtils;
import android.text.method.DigitsKeyListener;
import android.util.Base64;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Schermata unica: stato, tasti fissi, e quattro schede (Mouse, Frecce, Numeri, App). */
public final class MainActivity extends Activity {

    private static final int BG = 0xFF101014;
    private static final int PANEL_MOUSE = 0;
    private static final int PANEL_ARROWS = 1;
    private static final int PANEL_NUMBERS = 2;
    private static final int PANEL_APPS = 3;

    private RemoteClient client;
    private TextView statusView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean started;
    private boolean suppressReconnect;
    private RemoteClient.State state = RemoteClient.State.DISCONNECTED;
    private AlertDialog dialog;
    private String currentIp = "";
    private String currentPin = "";

    // scrittura
    private BufferEditText textBox;
    private LinearLayout textBar;
    private String prevText = "";
    private boolean suppressSync;
    private long lastTextToast;

    // timer di spegnimento
    private Button timerButton;
    private long timerEndLocal; // SystemClock.elapsedRealtime() di scadenza, 0 = nessun timer

    // schede
    private final View[] panels = new View[4];
    private final Button[] tabs = new Button[4];
    private int currentPanel = PANEL_MOUSE;
    private TrackpadView padMouse;
    private TrackpadView padNav;

    // app della TV
    private LinearLayout appsList;
    private TextView appsInfo;
    private boolean appsLoaded;
    private final List<String[]> apps = new ArrayList<>(); // {pacchetto, nome}

    private final Runnable reconnect = new Runnable() {
        @Override
        public void run() {
            if (started && !suppressReconnect && !currentIp.isEmpty()
                    && state != RemoteClient.State.CONNECTED
                    && state != RemoteClient.State.CONNECTING) {
                client.connect(currentIp, currentPin);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        client = new RemoteClient(deviceName(), new RemoteClient.Listener() {
            @Override
            public void onState(RemoteClient.State s, String detail) {
                state = s;
                switch (s) {
                    case CONNECTING:
                        setStatus("Connessione a " + detail + "...", 0xFFFFC107);
                        break;
                    case CONNECTED:
                        setStatus("Connesso: " + detail, 0xFF4CAF50);
                        suppressReconnect = false;
                        Prefs.upsert(MainActivity.this, currentIp, currentPin, detail);
                        Prefs.setLastIp(MainActivity.this, currentIp);
                        apps.clear();
                        appsLoaded = false;
                        if (currentPanel == PANEL_APPS) {
                            requestApps();
                        } else {
                            renderApps();
                        }
                        break;
                    case AUTH_FAILED:
                        setStatus("PIN errato", 0xFFF44336);
                        showConnectDialog("PIN errato, oppure la TV non richiede il PIN (in quel caso lascia il campo vuoto).");
                        break;
                    default:
                        setStatus(detail.isEmpty() ? "Non connesso" : "Non connesso - " + detail, 0xFFBBBBBB);
                        renderApps();
                        if (started && !suppressReconnect) handler.postDelayed(reconnect, 2000);
                        break;
                }
            }

            @Override
            public void onServerMessage(String line) {
                handleServerMessage(line);
            }
        });

        setContentView(buildUi());
        applyOptions();
        showPanel(PANEL_MOUSE);
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        Prefs.Tv last = Prefs.last(this);
        if (last == null) {
            showConnectDialog("Sulla TV apri l'app \"TV Mouse Ricevitore\": vedrai IP e PIN.");
        } else {
            suppressReconnect = false;
            connectTo(last.ip, last.pin);
        }
    }

    @Override
    protected void onStop() {
        started = false;
        clearBuffer();
        handler.removeCallbacks(reconnect);
        client.disconnect();
        state = RemoteClient.State.DISCONNECTED;
        setStatus("Non connesso", 0xFFBBBBBB);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
        super.onDestroy();
    }

    private void connectTo(String ip, String pin) {
        currentIp = ip;
        currentPin = pin;
        handler.removeCallbacks(reconnect);
        client.connect(ip, pin);
    }

    // ------------------------------------------------------------ messaggi dalla TV

    private void handleServerMessage(String line) {
        String[] a = line.split("\\|", -1);
        switch (a[0]) {
            case "APP":
                if (a.length >= 3) apps.add(new String[]{a[1], decodeLabel(a[2], a[1])});
                break;
            case "APPSEND":
                appsLoaded = true;
                renderApps();
                break;
            case "MAC":
                if (a.length >= 2 && !currentIp.isEmpty()) Prefs.setMacs(this, currentIp, a[1]);
                break;
            case "BYE":
                suppressReconnect = true;
                toastLong("Un altro telefono si è collegato alla TV.");
                break;
            case "TIMER":
                if (a.length >= 2) {
                    try {
                        applyTimerFromTv(Integer.parseInt(a[1].trim()));
                    } catch (NumberFormatException ignored) {
                        // messaggio non valido
                    }
                }
                break;
            case "ERR":
                handleError(a.length >= 2 ? a[1] : "");
                break;
            default:
                break;
        }
    }

    private void handleError(String code) {
        long now = System.currentTimeMillis();
        switch (code) {
            case "NOFIELD":
                if (now - lastTextToast > 4000) {
                    lastTextToast = now;
                    toastLong("Non riesco a scrivere: sulla TV seleziona prima un campo di testo (per le password serve il bridge ADB).");
                }
                break;
            case "CHARS":
                if (now - lastTextToast > 4000) {
                    lastTextToast = now;
                    toastLong("Questo campo accetta solo caratteri base: accenti, emoji e alcuni simboli sono stati saltati.");
                }
                break;
            case "POWER":
                toastLong("La TV non si è spenta: senza il bridge ADB lo spegnimento non è garantito e dipende dalla TV.");
                break;
            case "ADB_OFF":
                toastLong("Sulla TV il bridge ADB non è attivo: abilita il debug di rete nelle Opzioni sviluppatore (vedi guida).");
                break;
            case "ADB_FAIL":
                toastLong("Il bridge ADB non risponde: sulla TV conferma la richiesta di debug (\"Consenti sempre\") e riprova.");
                break;
            default:
                break;
        }
    }

    private static String decodeLabel(String b64, String fallback) {
        try {
            String s = new String(Base64.decode(b64, Base64.NO_WRAP), StandardCharsets.UTF_8);
            return s.isEmpty() ? fallback : s;
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    // ------------------------------------------------------------ interfaccia

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        int p = dp(12);
        root.setPadding(p, dp(12), p, p);

        // riga in alto: solo lo stato della connessione, a tutta larghezza
        statusView = new TextView(this);
        statusView.setTextSize(15);
        statusView.setTextColor(0xFFBBBBBB);
        statusView.setMaxLines(2);
        statusView.setEllipsize(TextUtils.TruncateAt.END);
        statusView.setText("Non connesso");
        root.addView(statusView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // tutti i tasti stanno in fondo, sotto al trackpad
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);

        // Scrivi + Opzioni + TV
        bottom.addView(row(48,
                smallButton("Scrivi", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        toggleTextBar();
                    }
                }),
                smallButton("Opzioni", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showOptions();
                    }
                }),
                smallButton("TV", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showTvList();
                    }
                })));

        // tasti sempre visibili
        bottom.addView(row(60,
                key("CH −", "CHDOWN", false),
                key("CH +", "CHUP", false)));
        bottom.addView(row(60,
                key("VOL −", "VOLDOWN", true),
                key("MUTE", "MUTE", false),
                key("VOL +", "VOLUP", true)));
        Button back = key("Indietro", "BACK", false);
        Button home = key("Home", "HOME", false);
        timerButton = new Button(this);
        timerButton.setAllCaps(false);
        timerButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showTimerDialog();
            }
        });
        Button power = powerKey();
        for (Button b : new Button[]{back, home, timerButton, power}) {
            b.setTextSize(14);
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            b.setPadding(dp(4), 0, dp(4), 0);
        }
        bottom.addView(row(60, back, home, timerButton, power));
        updateTimerButton();

        // barra di scrittura (nascosta finché non premi "Scrivi")
        textBar = new LinearLayout(this);
        textBar.setOrientation(LinearLayout.VERTICAL);
        textBar.setVisibility(View.GONE);

        textBox = new BufferEditText(this);
        textBox.setHint("Scrivi qui: il testo va sulla TV");
        textBox.setHintTextColor(0xFF777788);
        textBox.setTextColor(0xFFFFFFFF);
        textBox.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        textBox.setImeOptions(EditorInfo.IME_ACTION_SEND);
        textBox.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                // non serve
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                // non serve
            }

            @Override
            public void afterTextChanged(Editable s) {
                onBufferChanged(s.toString());
            }
        });
        textBox.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                sendEnter();
                return true;
            }
        });
        textBar.addView(textBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button paste = smallButton("Incolla", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pasteFromClipboard();
            }
        });
        Button reset = smallButton("Azzera", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clearBuffer(); // svuota solo la casella sul telefono, non tocca la TV
            }
        });
        Button enter = smallButton("Invio", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendEnter();
            }
        });
        textBar.addView(row(44, paste, reset, enter));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.topMargin = dp(6);
        final LinearLayout.LayoutParams textBarLp = barLp;

        // linguette
        String[] tabNames = {"Mouse", "Frecce", "Numeri", "App"};
        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams tabLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tabLp.topMargin = dp(8);
        for (int i = 0; i < tabNames.length; i++) {
            final int idx = i;
            Button b = new Button(this);
            b.setText(tabNames[i]);
            b.setAllCaps(false);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showPanel(idx);
                }
            });
            tabs[i] = b;
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, dp(44), 1f);
            bp.leftMargin = dp(2);
            bp.rightMargin = dp(2);
            tabRow.addView(b, bp);
        }
        root.addView(tabRow, tabLp);

        // pannelli
        FrameLayout host = new FrameLayout(this);
        LinearLayout.LayoutParams hostLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        hostLp.topMargin = dp(8);
        root.addView(host, hostLp);

        panels[PANEL_MOUSE] = buildMousePanel();
        panels[PANEL_ARROWS] = buildArrowsPanel();
        panels[PANEL_NUMBERS] = buildNumbersPanel();
        panels[PANEL_APPS] = buildAppsPanel();
        for (View panel : panels) {
            host.addView(panel, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        root.addView(textBar, textBarLp); // sopra ai tasti, sotto al trackpad
        root.addView(bottom);
        return root;
    }

    private View buildMousePanel() {
        padMouse = new TrackpadView(this);
        padMouse.setListener(new TrackpadView.Listener() {
            @Override
            public void onMove(float dx, float dy) {
                client.sendMove(dx, dy);
            }

            @Override
            public void onClick() {
                client.send("CLICK");
            }

            @Override
            public void onLongPress() {
                client.send("LONG");
            }

            @Override
            public void onScroll(int dir) {
                client.send("SCROLL|" + dir);
            }
        });
        return padMouse;
    }

    private View buildArrowsPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.addView(row(56, new View(this), key("▲", "KEY|19", true), new View(this)));
        panel.addView(row(56, key("◀", "KEY|21", true), key("OK", "KEY|23", false),
                key("▶", "KEY|22", true)));
        panel.addView(row(56, key("Menu", "KEY|82", false), key("▼", "KEY|20", true),
                key("Ingressi", "KEY|178", false)));

        padNav = new TrackpadView(this);
        padNav.setNavigation(true);
        padNav.setListener(new TrackpadView.Listener() {
            @Override
            public void onMove(float dx, float dy) {
                // in questo modo non si muove il puntatore
            }

            @Override
            public void onClick() {
                client.send("KEY|23");
            }

            @Override
            public void onLongPress() {
                // niente
            }

            @Override
            public void onScroll(int dir) {
                // niente
            }

            @Override
            public void onNavigate(int dir) {
                switch (dir) {
                    case TrackpadView.DIR_UP:
                        client.send("KEY|19");
                        break;
                    case TrackpadView.DIR_DOWN:
                        client.send("KEY|20");
                        break;
                    case TrackpadView.DIR_LEFT:
                        client.send("KEY|21");
                        break;
                    default:
                        client.send("KEY|22");
                        break;
                }
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = dp(8);
        panel.addView(padNav, lp);
        return panel;
    }

    private View buildNumbersPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        Button prev = key("Prec.", "KEY|88", false);
        Button rew = key("Indietro", "KEY|89", false);
        Button play = key("Play/Pausa", "KEY|85", false);
        Button ff = key("Avanti", "KEY|90", false);
        Button next = key("Succ.", "KEY|87", false);
        for (Button b : new Button[]{prev, rew, play, ff, next}) b.setTextSize(12);
        panel.addView(row(52, prev, rew, play, ff, next));
        for (int r = 0; r < 3; r++) {
            panel.addView(row(52, digit(1 + 3 * r), digit(2 + 3 * r), digit(3 + 3 * r)));
        }
        panel.addView(row(52, new View(this), digit(0), new View(this)));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(panel, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private Button digit(int n) {
        // KEYCODE_0 = 7, KEYCODE_1 = 8 ... KEYCODE_9 = 16
        return key(String.valueOf(n), "KEY|" + (7 + n), false);
    }

    private View buildAppsPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        appsInfo = new TextView(this);
        appsInfo.setTextColor(0xFFBBBBBB);
        appsInfo.setTextSize(14);
        header.addView(appsInfo, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(smallButton("Aggiorna", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (state == RemoteClient.State.CONNECTED) requestApps();
            }
        }));
        panel.addView(header);

        appsList = new LinearLayout(this);
        appsList.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(appsList, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        renderApps();
        return panel;
    }

    private void showPanel(int idx) {
        currentPanel = idx;
        for (int i = 0; i < panels.length; i++) {
            panels[i].setVisibility(i == idx ? View.VISIBLE : View.GONE);
            tabs[i].setAlpha(i == idx ? 1f : 0.55f);
        }
        if (idx == PANEL_APPS && state == RemoteClient.State.CONNECTED && !appsLoaded) requestApps();
    }

    private void requestApps() {
        apps.clear();
        appsLoaded = false;
        renderApps();
        client.send("APPS");
    }

    private void renderApps() {
        if (appsList == null) return;
        appsList.removeAllViews();
        if (state != RemoteClient.State.CONNECTED) {
            appsInfo.setText("Collegati a una TV per vedere le sue app.");
            return;
        }
        if (!appsLoaded) {
            appsInfo.setText("Caricamento...");
            return;
        }
        if (apps.isEmpty()) {
            appsInfo.setText("Nessuna app trovata sulla TV.");
            return;
        }
        appsInfo.setText(apps.size() + " app sulla TV");
        for (int i = 0; i < apps.size(); i += 2) {
            View second = i + 1 < apps.size() ? appButton(apps.get(i + 1)) : new View(this);
            appsList.addView(row(52, appButton(apps.get(i)), second));
        }
    }

    private Button appButton(final String[] app) {
        Button b = new Button(this);
        b.setText(app[1]);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setSingleLine(true);
        b.setEllipsize(TextUtils.TruncateAt.END);
        // colore diverso dai tasti delle funzioni: le app sono verde acqua con testo bianco
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF00897B));
        b.setTextColor(0xFFFFFFFF);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                haptic(v, HapticFeedbackConstants.KEYBOARD_TAP);
                client.send("LAUNCH|" + app[0]);
            }
        });
        return b;
    }

    private LinearLayout row(int heightDp, View... buttons) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = dp(6);
        r.setLayoutParams(rp);
        for (View b : buttons) {
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, dp(heightDp), 1f);
            bp.leftMargin = dp(3);
            bp.rightMargin = dp(3);
            r.addView(b, bp);
        }
        return r;
    }

    private Button smallButton(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setOnClickListener(l);
        return b;
    }

    /** Tasto che invia un comando. Se repeat e' true, tenendolo premuto il comando si ripete. */
    private Button key(String label, final String command, boolean repeat) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(17);
        if (!repeat) {
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    haptic(v, HapticFeedbackConstants.KEYBOARD_TAP);
                    client.send(command);
                }
            });
        } else {
            b.setOnTouchListener(new View.OnTouchListener() {
                private final Runnable repeater = new Runnable() {
                    @Override
                    public void run() {
                        client.send(command);
                        handler.postDelayed(this, 150);
                    }
                };

                @Override
                public boolean onTouch(View v, MotionEvent e) {
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            v.setPressed(true);
                            haptic(v, HapticFeedbackConstants.KEYBOARD_TAP);
                            client.send(command);
                            handler.postDelayed(repeater, 450);
                            return true;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            v.setPressed(false);
                            handler.removeCallbacks(repeater);
                            return true;
                        default:
                            return true;
                    }
                }
            });
        }
        return b;
    }

    /** Lo spegnimento richiede una pressione lunga, per evitare tocchi accidentali. */
    private Button powerKey() {
        Button b = new Button(this);
        b.setText("⏻ Spegni");
        b.setAllCaps(false);
        b.setTextSize(17);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Toast.makeText(MainActivity.this, "Tieni premuto per spegnere la TV", Toast.LENGTH_SHORT).show();
            }
        });
        b.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                haptic(v, HapticFeedbackConstants.LONG_PRESS);
                client.send("POWER");
                return true;
            }
        });
        return b;
    }

    private void haptic(View v, int type) {
        if (Prefs.haptics(this)) v.performHapticFeedback(type);
    }

    // ------------------------------------------------------------ tasti volume del telefono

    private boolean volumeKeysActive() {
        return Prefs.volumeKeys(this) && state == RemoteClient.State.CONNECTED;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (volumeKeysActive()) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                client.send("VOLUP");
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                client.send("VOLDOWN");
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (volumeKeysActive()
                && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    // ------------------------------------------------------------ opzioni

    private void applyOptions() {
        float scale = Prefs.sensitivity(this);
        boolean haptics = Prefs.haptics(this);
        padMouse.setSensitivityScale(scale);
        padMouse.setHaptics(haptics);
        padNav.setHaptics(haptics);
    }

    private void showOptions() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, dp(8), pad, 0);

        final TextView sensLabel = new TextView(this);
        final SeekBar sens = new SeekBar(this);
        sens.setMax(30); // 0,5 ... 2,0 a passi di 0,05
        sens.setProgress(Math.round((Prefs.sensitivity(this) - 0.5f) / 0.05f));
        sensLabel.setText(sensitivityText(sens.getProgress()));
        sens.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                sensLabel.setText(sensitivityText(progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                // non serve
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                // non serve
            }
        });
        box.addView(sensLabel);
        box.addView(sens);

        final CheckBox haptics = new CheckBox(this);
        haptics.setText("Vibrazione sui tasti");
        haptics.setChecked(Prefs.haptics(this));
        box.addView(haptics);

        final CheckBox volume = new CheckBox(this);
        volume.setText("I tasti volume del telefono comandano la TV (con l'app aperta)");
        volume.setChecked(Prefs.volumeKeys(this));
        box.addView(volume);

        new AlertDialog.Builder(this)
                .setTitle("Opzioni")
                .setView(box)
                .setPositiveButton("Salva", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        Prefs.saveOptions(MainActivity.this, 0.5f + sens.getProgress() * 0.05f,
                                haptics.isChecked(), volume.isChecked());
                        applyOptions();
                    }
                })
                .setNegativeButton("Annulla", null)
                .show();
    }

    private static String sensitivityText(int progress) {
        return String.format(Locale.ROOT, "Velocità del puntatore: x%.2f", 0.5f + progress * 0.05f);
    }

    // ------------------------------------------------------------ testo verso la TV

    private void toggleTextBar() {
        final InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (textBar.getVisibility() == View.VISIBLE) {
            clearBuffer();
            textBar.setVisibility(View.GONE);
            imm.hideSoftInputFromWindow(textBox.getWindowToken(), 0);
        } else {
            textBar.setVisibility(View.VISIBLE);
            textBox.requestFocus();
            textBox.post(new Runnable() {
                @Override
                public void run() {
                    imm.showSoftInput(textBox, InputMethodManager.SHOW_IMPLICIT);
                }
            });
        }
    }

    /** Svuota la casella sul telefono senza inviare nulla alla TV. */
    private void clearBuffer() {
        if (textBox == null) return;
        suppressSync = true;
        textBox.setText("");
        suppressSync = false;
        prevText = "";
    }

    private void sendEnter() {
        client.send("ENTER");
        clearBuffer();
    }

    private void pasteFromClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) {
            Toast.makeText(this, "Gli appunti sono vuoti", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipData data = cm.getPrimaryClip();
        if (data == null || data.getItemCount() == 0) return;
        CharSequence clip = data.getItemAt(0).coerceToText(this);
        if (clip == null) return;
        String text = clip.toString().replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
        if (text.length() > 2000) text = text.substring(0, 2000);
        textBox.append(text); // il TextWatcher invia il testo alla TV
    }

    /**
     * Il cursore della casella è sempre in fondo, quindi ogni modifica è "togli la coda vecchia,
     * aggiungi la coda nuova": la TV riceve DEL|n e TEXT|base64.
     */
    private void onBufferChanged(String now) {
        if (suppressSync) {
            prevText = now;
            return;
        }
        String before = prevText;
        prevText = now;

        int max = Math.min(before.length(), now.length());
        int common = 0;
        while (common < max && before.charAt(common) == now.charAt(common)) common++;
        if (common > 0 && Character.isHighSurrogate(now.charAt(common - 1))) common--; // non spezzare una emoji

        String removed = before.substring(common);
        String added = now.substring(common);

        int del = removed.codePointCount(0, removed.length());
        if (del > 0) client.send("DEL|" + del);

        int i = 0;
        while (i < added.length()) {
            int left = added.codePointCount(i, added.length());
            int end = added.offsetByCodePoints(i, Math.min(100, left));
            String piece = added.substring(i, end);
            client.send("TEXT|" + Base64.encodeToString(piece.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
            i = end;
        }
    }

    // ------------------------------------------------------------ timer di spegnimento

    private final Runnable timerTick = new Runnable() {
        @Override
        public void run() {
            updateTimerButton();
            if (timerEndLocal > 0) handler.postDelayed(this, 15000);
        }
    };

    private int timerSecondsLeft() {
        if (timerEndLocal == 0) return 0;
        long left = timerEndLocal - SystemClock.elapsedRealtime();
        return left <= 0 ? 0 : (int) ((left + 999) / 1000);
    }

    /** La TV comunica quanti secondi mancano allo spegnimento (0 = nessun timer). */
    private void applyTimerFromTv(int seconds) {
        timerEndLocal = seconds > 0 ? SystemClock.elapsedRealtime() + seconds * 1000L : 0;
        handler.removeCallbacks(timerTick);
        updateTimerButton();
        if (timerEndLocal > 0) handler.postDelayed(timerTick, 15000);
    }

    private void updateTimerButton() {
        if (timerButton == null) return;
        int s = timerSecondsLeft();
        if (s <= 0) {
            timerEndLocal = 0;
            timerButton.setText("Timer");
        } else {
            timerButton.setText("Timer " + ((s + 59) / 60) + "m");
        }
    }

    private void showTimerDialog() {
        if (state != RemoteClient.State.CONNECTED) {
            Toast.makeText(this, "Collegati prima a una TV", Toast.LENGTH_SHORT).show();
            return;
        }
        final int[] presets = {15, 30, 45, 60, 90, 120};
        final boolean active = timerSecondsLeft() > 0;
        List<String> items = new ArrayList<>();
        if (active) items.add("Annulla il timer (restano " + ((timerSecondsLeft() + 59) / 60) + " min)");
        for (int m : presets) items.add("Spegni tra " + describeMinutes(m));
        items.add("Altro numero di minuti...");

        new AlertDialog.Builder(this)
                .setTitle("Timer di spegnimento")
                .setItems(items.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (active && which == 0) {
                            setTvTimer(0);
                            return;
                        }
                        int index = active ? which - 1 : which;
                        if (index < presets.length) {
                            setTvTimer(presets[index]);
                        } else {
                            showCustomTimerDialog();
                        }
                    }
                })
                .show();
    }

    private void showCustomTimerDialog() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(3)});
        input.setHint("Minuti (da 1 a 720)");
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, dp(8), pad, 0);
        box.addView(input);

        final AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Spegni la TV tra...")
                .setView(box)
                .setPositiveButton("Imposta", null)
                .setNegativeButton("Annulla", null)
                .create();
        d.show();
        d.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int minutes;
                try {
                    minutes = Integer.parseInt(input.getText().toString().trim());
                } catch (NumberFormatException e) {
                    minutes = 0;
                }
                if (minutes < 1 || minutes > 720) {
                    Toast.makeText(MainActivity.this, "Inserisci un numero da 1 a 720", Toast.LENGTH_SHORT).show();
                    return;
                }
                d.dismiss();
                setTvTimer(minutes);
            }
        });
    }

    private void setTvTimer(int minutes) {
        client.send("TIMER|" + minutes);
        Toast.makeText(this, minutes == 0
                ? "Timer annullato"
                : "La TV si spegnerà tra " + describeMinutes(minutes), Toast.LENGTH_SHORT).show();
    }

    private static String describeMinutes(int m) {
        if (m < 60) return m == 1 ? "1 minuto" : m + " minuti";
        int hours = m / 60;
        int rest = m % 60;
        String h = hours == 1 ? "1 ora" : hours + " ore";
        return rest == 0 ? h : h + " e " + rest + " minuti";
    }

    // ------------------------------------------------------------ elenco TV e collegamento

    private void showTvList() {
        final List<Prefs.Tv> list = Prefs.tvs(this);
        if (list.isEmpty()) {
            showConnectDialog(null);
            return;
        }
        String[] items = new String[list.size() + 1];
        for (int i = 0; i < list.size(); i++) items[i] = list.get(i).label();
        items[list.size()] = "+ Aggiungi o cerca una TV...";

        final AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Scegli la TV (tieni premuto per altre azioni)")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int which) {
                        if (which < list.size()) {
                            Prefs.Tv t = list.get(which);
                            suppressReconnect = false;
                            connectTo(t.ip, t.pin);
                        } else {
                            showConnectDialog(null);
                        }
                    }
                })
                .create();
        d.show();
        d.getListView().setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                if (position >= list.size()) return false;
                d.dismiss();
                showTvOptions(list.get(position));
                return true;
            }
        });
    }

    private void showTvOptions(final Prefs.Tv tv) {
        final String wake = "Accendi con Wake-on-LAN";
        final String remove = "Elimina dall'elenco";
        final String[] options = {wake, remove};
        new AlertDialog.Builder(this)
                .setTitle(tv.label())
                .setItems(options, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int which) {
                        if (options[which].equals(wake)) {
                            if (tv.macs.isEmpty()) {
                                toastLong("Indirizzo MAC non ancora noto: collegati una volta alla TV con il debug ADB attivo, poi riprova.");
                            } else {
                                WakeOnLan.send(tv.macs, tv.ip);
                                toastLong("Segnale di accensione inviato. Funziona solo se la TV supporta Wake-on-LAN ed è abilitato.");
                            }
                        } else {
                            Prefs.remove(MainActivity.this, tv.ip);
                            Toast.makeText(MainActivity.this, "TV eliminata", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .show();
    }

    private void showConnectDialog(String note) {
        if (isFinishing()) return;
        if (dialog != null && dialog.isShowing()) dialog.dismiss();

        Prefs.Tv last = Prefs.last(this);
        String ipDefault = !currentIp.isEmpty() ? currentIp : (last != null ? last.ip : "");
        String pinDefault = !currentIp.isEmpty() ? currentPin : (last != null ? last.pin : "");

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, dp(8), pad, 0);

        TextView info = new TextView(this);
        info.setText(note != null ? note
                : "Inserisci IP e PIN mostrati sulla TV, oppure cerca la TV in rete.");
        box.addView(info);

        final EditText ip = new EditText(this);
        ip.setHint("IP della TV (es. 192.168.43.20)");
        ip.setKeyListener(DigitsKeyListener.getInstance("0123456789."));
        ip.setText(ipDefault);
        box.addView(ip);

        final EditText pin = new EditText(this);
        pin.setHint("PIN a 6 cifre (vuoto se la TV non lo richiede)");
        pin.setInputType(InputType.TYPE_CLASS_NUMBER);
        pin.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
        pin.setText(pinDefault);
        box.addView(pin);

        final Button scan = new Button(this);
        scan.setText("Cerca TV in rete");
        scan.setAllCaps(false);
        scan.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startScan(scan, ip);
            }
        });
        box.addView(scan);

        dialog = new AlertDialog.Builder(this)
                .setTitle("Collega la TV")
                .setView(box)
                .setPositiveButton("Collega", null)
                .setNegativeButton("Annulla", null)
                .create();
        dialog.show();
        // override per non chiudere il dialogo se i dati non sono validi
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String host = ip.getText().toString().trim();
                String code = pin.getText().toString().trim();
                if (!host.matches("^\\d{1,3}(\\.\\d{1,3}){3}$")) {
                    Toast.makeText(MainActivity.this, "Indirizzo IP non valido", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!code.isEmpty() && !code.matches("^\\d{6}$")) {
                    Toast.makeText(MainActivity.this, "Il PIN ha 6 cifre (o lascialo vuoto)", Toast.LENGTH_SHORT).show();
                    return;
                }
                dialog.dismiss();
                suppressReconnect = false;
                connectTo(host, code);
            }
        });
    }

    private void startScan(final Button scanButton, final EditText ipField) {
        scanButton.setEnabled(false);
        scanButton.setText("Ricerca in corso...");
        Discovery.scan(new Discovery.Callback() {
            @Override
            public void onResult(final List<Discovery.Found> found) {
                if (isFinishing()) return;
                scanButton.setEnabled(true);
                scanButton.setText("Cerca TV in rete");
                if (found.isEmpty()) {
                    toastLong("Nessuna TV trovata. Controlla che l'app TV sia attiva e inserisci l'IP a mano.");
                } else if (found.size() == 1) {
                    ipField.setText(found.get(0).ip);
                } else {
                    String[] items = new String[found.size()];
                    for (int i = 0; i < items.length; i++) items[i] = found.get(i).toString();
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Scegli la TV")
                            .setItems(items, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface d, int which) {
                                    ipField.setText(found.get(which).ip);
                                }
                            })
                            .show();
                }
            }
        });
    }

    // ------------------------------------------------------------ utilita'

    private void setStatus(String text, int color) {
        statusView.setText(text);
        statusView.setTextColor(color);
    }

    private void toastLong(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static String deviceName() {
        String m = Build.MODEL == null ? "Telefono" : Build.MODEL.replaceAll("[^A-Za-z0-9 _.-]", "?");
        if (m.length() > 30) m = m.substring(0, 30);
        return m.isEmpty() ? "Telefono" : m;
    }
}
