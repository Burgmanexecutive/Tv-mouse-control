package app.tvmouse.tv;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** Schermata della TV: mostra IP e PIN da digitare sul telefono e lo stato dei servizi. */
public final class MainActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView ipView;
    private TextView pinView;
    private TextView serviceView;
    private TextView adbView;
    private EditText portField;
    private Button pinToggle;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            updateStatus();
            handler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(48);
        root.setPadding(pad, dp(32), pad, dp(32));

        root.addView(text("TV Mouse - ricevitore", 34));
        ipView = text("", 24);
        ipView.setTextColor(Color.parseColor("#FFEA00")); // giallo acceso: l'IP si legge bene da lontano
        ipView.setTypeface(ipView.getTypeface(), android.graphics.Typeface.BOLD);
        pinView = text("", 40);
        serviceView = text("", 24);
        adbView = text("", 22);
        root.addView(ipView);
        root.addView(pinView);
        root.addView(serviceView);
        root.addView(adbView);

        root.addView(text("Sul telefono apri TV Mouse, premi \"TV\" e scegli \"Aggiungi o cerca una TV\": inserisci l'IP e il PIN qui sopra (se il PIN è attivo).", 20));

        root.addView(button("1) Apri impostazioni di accessibilita'", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAccessibilitySettings();
            }
        }));
        root.addView(button("Genera un nuovo PIN", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Prefs.newPin(MainActivity.this);
                updateStatus();
            }
        }));

        pinToggle = button("", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Prefs.setRequirePin(MainActivity.this, !Prefs.requirePin(MainActivity.this));
                updateStatus();
            }
        });
        root.addView(pinToggle);

        root.addView(text("2) Canali, spegnimento, frecce, tasti numerici, riproduzione e Wake-on-LAN: attiva il debug ADB di rete nelle Opzioni sviluppatore, poi prova qui (porta di solito 5555):", 20));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        portField = new EditText(this);
        portField.setInputType(InputType.TYPE_CLASS_NUMBER);
        portField.setText(String.valueOf(Prefs.adbPort(this)));
        portField.setTextSize(22);
        portField.setBackground(shape(Color.WHITE, Color.parseColor("#1565C0"), 3));
        portField.setTextColor(Color.BLACK);
        portField.setPadding(dp(12), dp(8), dp(12), dp(8));
        row.addView(portField, new LinearLayout.LayoutParams(dp(160), ViewGroup.LayoutParams.WRAP_CONTENT));
        Button test = button("Salva e prova ADB", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                testAdb();
            }
        });
        row.addView(test);
        root.addView(row);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    private void updateStatus() {
        List<String> ips = NetUtil.localIps();
        ipView.setText("Indirizzo IP della TV: " + (ips.isEmpty() ? "nessuna rete" : join(ips)));
        boolean needPin = Prefs.requirePin(this);
        pinView.setText(needPin
                ? "PIN: " + Prefs.pin(this)
                : "PIN: non richiesto (chiunque sia nella tua rete può collegarsi)");
        pinToggle.setText(needPin ? "PIN richiesto: SÌ (tocca per disattivarlo)" : "PIN richiesto: NO (tocca per attivarlo)");
        serviceView.setText(RemoteService.isRunning()
                ? "Servizio mouse: ATTIVO"
                : "Servizio mouse: NON ATTIVO - abilitalo in Accessibilita'");
        adbView.setText("Bridge ADB (canali, spegnimento): " + AdbBridge.get(this).status());
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this,
                    "Apri a mano: Impostazioni > Preferenze dispositivo > Accessibilita' > TV Mouse Ricevitore",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void testAdb() {
        int port = 5555;
        try {
            port = Integer.parseInt(portField.getText().toString().trim());
        } catch (NumberFormatException ignored) {
            // uso 5555
        }
        if (port < 1 || port > 65535) port = 5555;
        Prefs.setAdbPort(this, port);
        adbView.setText("Bridge ADB: prova in corso... se compare una richiesta sullo schermo scegli \"Consenti sempre\"");
        AdbBridge.get(this).test(new AdbBridge.Result() {
            @Override
            public void done(boolean ok, String code) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        updateStatus();
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------ utilita' UI

    private TextView text(String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        t.setLayoutParams(lp);
        return t;
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(20);
        b.setOnClickListener(l);
        styleButton(b);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        b.setLayoutParams(lp);
        return b;
    }

    private GradientDrawable shape(int fill, int stroke, int strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(10));
        g.setStroke(dp(strokeDp), stroke);
        return g;
    }

    /** Blu scuro a riposo; giallo acceso con bordo bianco e ingrandito quando selezionato (frecce/mouse). */
    private void styleButton(final Button b) {
        GradientDrawable rest = shape(Color.parseColor("#1565C0"), Color.parseColor("#90CAF9"), 2);
        GradientDrawable active = shape(Color.parseColor("#FFD600"), Color.WHITE, 5);
        GradientDrawable pressed = shape(Color.parseColor("#FF6D00"), Color.WHITE, 5);
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_pressed}, pressed);
        sl.addState(new int[]{android.R.attr.state_focused}, active);
        sl.addState(new int[]{android.R.attr.state_hovered}, active);
        sl.addState(new int[]{}, rest);
        b.setBackground(sl);
        b.setPadding(dp(24), dp(12), dp(24), dp(12));
        b.setFocusable(true);
        b.setTextColor(new android.content.res.ColorStateList(
                new int[][]{
                        new int[]{android.R.attr.state_pressed},
                        new int[]{android.R.attr.state_focused},
                        new int[]{android.R.attr.state_hovered},
                        new int[]{}},
                new int[]{Color.BLACK, Color.BLACK, Color.BLACK, Color.WHITE}));
        View.OnFocusChangeListener grow = new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean has) {
                v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(100).start();
            }
        };
        b.setOnFocusChangeListener(grow);
        b.setOnHoverListener(new View.OnHoverListener() {
            @Override
            public boolean onHover(View v, android.view.MotionEvent e) {
                int a = e.getActionMasked();
                if (a == android.view.MotionEvent.ACTION_HOVER_ENTER) {
                    v.animate().scaleX(1.06f).scaleY(1.06f).setDuration(100).start();
                } else if (a == android.view.MotionEvent.ACTION_HOVER_EXIT) {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(100).start();
                }
                return false;
            }
        });
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static String join(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (String s : items) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(s);
        }
        return sb.toString();
    }
}
