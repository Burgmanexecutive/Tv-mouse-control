package app.tvmouse.desktop;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Properties;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;

/** TV Mouse per PC e notebook (Windows, macOS, Linux): stesse funzioni dell'app del telefono. */
public final class Main {

    private static final Color BG = new Color(0x101014);
    private static final Color FN = new Color(0x3A3A44);      // tasti delle funzioni
    private static final Color APP = new Color(0x00897B);     // tasti/elenco delle app
    private static final Color PAD = new Color(0x1E1E26);

    private final File settingsFile = new File(System.getProperty("user.home"), ".tvmouse-desktop.properties");
    private final Properties settings = new Properties();

    private final JFrame frame = new JFrame("TV Mouse");
    private final JTextArea status = new JTextArea("Non connesso");
    private final JTextField ipField = new JTextField(12);
    private final JTextField pinField = new JTextField(6);
    private final JTextField textField = new JTextField();
    private final JSlider sensitivity = new JSlider(5, 40, 16);
    private final DefaultListModel<String[]> apps = new DefaultListModel<>();
    private final JLabel appsInfo = new JLabel("Premi Aggiorna per leggere le app della TV");
    private final JButton connectButton = new JButton("Connetti");
    private final JButton scanButton = new JButton("Cerca TV");
    private final Client client;
    private boolean connectedOnce;

    private Main() {
        load();
        String name = "PC";
        try {
            name = InetAddress.getLocalHost().getHostName();
        } catch (IOException ignored) {
            // uso il nome generico
        }
        client = new Client(name, new Client.Listener() {
            @Override
            public void onState(Client.State state, String detail) {
                handleState(state, detail);
            }

            @Override
            public void onServerMessage(String line) {
                handleServerMessage(line);
            }
        });
        buildUi();
        installKeyboardShortcuts();
        String ip = settings.getProperty("ip", "");
        if (!ip.isEmpty()) client.connect(ip, settings.getProperty("pin", ""));
    }

    // ------------------------------------------------------------ interfaccia

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBackground(BG);
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // in alto: stato a tutta larghezza + connessione
        JPanel top = new JPanel(new BorderLayout(0, 6));
        top.setOpaque(false);
        status.setEditable(false);
        status.setFocusable(false);
        status.setLineWrap(true);
        status.setWrapStyleWord(true);
        status.setOpaque(false);
        status.setForeground(new Color(0xBBBBBB));
        status.setFont(status.getFont().deriveFont(Font.PLAIN, 15f));
        top.add(status, BorderLayout.NORTH);

        JPanel conn = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        conn.setOpaque(false);
        conn.add(label("IP TV"));
        ipField.setText(settings.getProperty("ip", ""));
        conn.add(ipField);
        conn.add(label("PIN"));
        pinField.setText(settings.getProperty("pin", ""));
        conn.add(pinField);
        style(connectButton, FN);
        style(scanButton, FN);
        connectButton.addActionListener(e -> toggleConnection());
        scanButton.addActionListener(e -> scan());
        conn.add(connectButton);
        conn.add(scanButton);
        top.add(conn, BorderLayout.SOUTH);
        root.add(top, BorderLayout.NORTH);

        // al centro: schede
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Mouse", mousePanel());
        tabs.addTab("Frecce", arrowsPanel());
        tabs.addTab("Numeri", numbersPanel());
        tabs.addTab("App", appsPanel());
        tabs.setFocusable(false);
        root.add(tabs, BorderLayout.CENTER);

        // in basso: scrittura + tasti delle funzioni
        JPanel bottom = new JPanel();
        bottom.setOpaque(false);
        bottom.setLayout(new javax.swing.BoxLayout(bottom, javax.swing.BoxLayout.Y_AXIS));

        JPanel text = new JPanel(new BorderLayout(6, 0));
        text.setOpaque(false);
        textField.setToolTipText("Scrivi qui il testo da inviare alla TV e premi Invio");
        textField.addActionListener(e -> sendText());
        text.add(textField, BorderLayout.CENTER);
        JPanel textButtons = new JPanel(new GridLayout(1, 3, 4, 0));
        textButtons.setOpaque(false);
        textButtons.add(fn("Invia testo", () -> sendText()));
        textButtons.add(fn("Cancella ⌫", () -> client.send("DEL|1")));
        textButtons.add(fn("Invio ↵", () -> client.send("ENTER")));
        text.add(textButtons, BorderLayout.EAST);
        bottom.add(text);
        bottom.add(javax.swing.Box.createVerticalStrut(6));

        bottom.add(buttonRow(fn("CH −", "CHDOWN"), fn("CH +", "CHUP")));
        bottom.add(buttonRow(repeat("VOL −", "VOLDOWN"), fn("MUTE", "MUTE"), repeat("VOL +", "VOLUP")));
        bottom.add(buttonRow(fn("Indietro", "BACK"), fn("Home", "HOME"),
                fn("Timer", () -> timerDialog()), fn("Spegni", () -> confirmPower())));
        root.add(bottom, BorderLayout.SOUTH);

        frame.setContentPane(root);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setMinimumSize(new Dimension(520, 640));
        frame.setSize(560, 760);
        frame.setLocationByPlatform(true);
        frame.setVisible(true);
    }

    private JLabel label(String s) {
        JLabel l = new JLabel(s);
        l.setForeground(Color.WHITE);
        return l;
    }

    private JPanel mousePanel() {
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setBackground(BG);
        p.add(new Trackpad(), BorderLayout.CENTER);
        JPanel s = new JPanel(new BorderLayout(6, 0));
        s.setOpaque(false);
        s.add(label("Sensibilità"), BorderLayout.WEST);
        sensitivity.setOpaque(false);
        sensitivity.setValue(Integer.parseInt(settings.getProperty("sens", "16")));
        sensitivity.setFocusable(false);
        s.add(sensitivity, BorderLayout.CENTER);
        p.add(s, BorderLayout.SOUTH);
        return p;
    }

    private JPanel arrowsPanel() {
        JPanel p = new JPanel(new GridLayout(3, 3, 6, 6));
        p.setBackground(BG);
        p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        p.add(new JPanel() {{ setOpaque(false); }});
        p.add(repeat("▲", "KEY|19"));
        p.add(new JPanel() {{ setOpaque(false); }});
        p.add(repeat("◀", "KEY|21"));
        p.add(fn("OK", "KEY|23"));
        p.add(repeat("▶", "KEY|22"));
        p.add(fn("Menu", "KEY|82"));
        p.add(repeat("▼", "KEY|20"));
        p.add(fn("Ingressi", "KEY|178"));
        return p;
    }

    private JPanel numbersPanel() {
        JPanel p = new JPanel(new GridLayout(5, 3, 6, 6));
        p.setBackground(BG);
        p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JPanel media = new JPanel(new GridLayout(1, 5, 4, 0));
        media.setOpaque(false);
        media.add(fn("Prec.", "KEY|88"));
        media.add(fn("Indietro", "KEY|89"));
        media.add(fn("Play/Pausa", "KEY|85"));
        media.add(fn("Avanti", "KEY|90"));
        media.add(fn("Succ.", "KEY|87"));
        JPanel wrap = new JPanel(new BorderLayout(0, 6));
        wrap.setBackground(BG);
        wrap.add(media, BorderLayout.NORTH);
        for (int i = 1; i <= 9; i++) p.add(fn(String.valueOf(i), "KEY|" + (7 + i)));
        p.add(new JPanel() {{ setOpaque(false); }});
        p.add(fn("0", "KEY|7"));
        p.add(new JPanel() {{ setOpaque(false); }});
        wrap.add(p, BorderLayout.CENTER);
        return wrap;
    }

    private JPanel appsPanel() {
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setBackground(BG);
        p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        appsInfo.setForeground(Color.WHITE);
        JPanel head = new JPanel(new BorderLayout(6, 0));
        head.setOpaque(false);
        head.add(appsInfo, BorderLayout.CENTER);
        head.add(fn("Aggiorna", () -> {
            apps.clear();
            appsInfo.setText("Lettura delle app...");
            client.send("APPS");
        }), BorderLayout.EAST);
        p.add(head, BorderLayout.NORTH);

        JList<String[]> list = new JList<>(apps);
        list.setBackground(APP);
        list.setForeground(Color.WHITE);
        list.setSelectionBackground(new Color(0xFFD600));
        list.setSelectionForeground(Color.BLACK);
        list.setFont(list.getFont().deriveFont(Font.PLAIN, 16f));
        list.setFixedCellHeight(34);
        list.setCellRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean focus) {
                Component c = super.getListCellRendererComponent(l, ((String[]) v)[1], i, sel, focus);
                ((JLabel) c).setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));
                return c;
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) launch(list.getSelectedValue());
            }
        });
        list.addKeyListener(new java.awt.event.KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) launch(list.getSelectedValue());
            }
        });
        p.add(new JScrollPane(list), BorderLayout.CENTER);
        p.add(fn("Avvia l'app selezionata", () -> launch(list.getSelectedValue())), BorderLayout.SOUTH);
        return p;
    }

    private void launch(String[] app) {
        if (app != null) client.send("LAUNCH|" + app[0]);
    }

    // ------------------------------------------------------------ pulsanti

    private void style(JButton b, Color bg) {
        b.setBackground(bg);
        b.setForeground(Color.WHITE);
        b.setOpaque(true);
        b.setFocusPainted(false);
        b.setFocusable(false);
        b.setFont(b.getFont().deriveFont(Font.PLAIN, 15f));
        b.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
    }

    /** Tasto che invia un comando. */
    private JButton fn(String label, String command) {
        return fn(label, () -> client.send(command));
    }

    private JButton fn(String label, Runnable action) {
        JButton b = new JButton(label);
        style(b, FN);
        b.addActionListener(e -> action.run());
        return b;
    }

    /** Tasto che si ripete tenendolo premuto (volume, frecce). */
    private JButton repeat(String label, String command) {
        JButton b = new JButton(label);
        style(b, FN);
        Timer t = new Timer(150, e -> client.send(command));
        Timer first = new Timer(450, e -> t.start());
        first.setRepeats(false);
        b.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                client.send(command);
                first.start();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                first.stop();
                t.stop();
            }
        });
        return b;
    }

    private JPanel buttonRow(JButton... buttons) {
        JPanel r = new JPanel(new GridLayout(1, buttons.length, 6, 0));
        r.setOpaque(false);
        r.setBorder(BorderFactory.createEmptyBorder(3, 0, 3, 0));
        for (JButton b : buttons) r.add(b);
        r.setMaximumSize(new Dimension(Integer.MAX_VALUE, 54));
        r.setPreferredSize(new Dimension(100, 54));
        return r;
    }

    // ------------------------------------------------------------ azioni

    private void sendText() {
        String t = textField.getText();
        if (t.isEmpty()) return;
        int[] cps = t.codePoints().toArray();
        for (int i = 0; i < cps.length; i += 100) {
            String piece = new String(cps, i, Math.min(100, cps.length - i));
            client.send("TEXT|" + Base64.getEncoder().encodeToString(piece.getBytes(StandardCharsets.UTF_8)));
        }
        textField.setText("");
    }

    private void confirmPower() {
        if (JOptionPane.showConfirmDialog(frame, "Spegnere la TV?", "TV Mouse",
                JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
            client.send("POWER");
        }
    }

    private void timerDialog() {
        String[] options = {"15 minuti", "30 minuti", "1 ora", "1 ora e mezza", "2 ore", "Altro...", "Annulla timer"};
        int[] minutes = {15, 30, 60, 90, 120, -1, 0};
        int c = JOptionPane.showOptionDialog(frame, "Spegni la TV tra:", "Timer di spegnimento",
                JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[1]);
        if (c < 0) return;
        int m = minutes[c];
        if (m < 0) {
            String in = JOptionPane.showInputDialog(frame, "Minuti (da 1 a 720):", "Timer", JOptionPane.QUESTION_MESSAGE);
            if (in == null) return;
            try {
                m = Integer.parseInt(in.trim());
            } catch (NumberFormatException e) {
                m = 0;
            }
            if (m < 1 || m > 720) {
                JOptionPane.showMessageDialog(frame, "Inserisci un numero da 1 a 720");
                return;
            }
        }
        client.send("TIMER|" + m);
    }

    private void toggleConnection() {
        if (client.isConnected()) {
            client.disconnect();
            setStatus("Non connesso", new Color(0xBBBBBB));
            connectButton.setText("Connetti");
            return;
        }
        String ip = ipField.getText().trim();
        if (ip.isEmpty()) {
            JOptionPane.showMessageDialog(frame, "Inserisci l'indirizzo IP mostrato sulla TV, oppure premi \"Cerca TV\".");
            return;
        }
        client.connect(ip, pinField.getText().trim());
    }

    private void scan() {
        scanButton.setEnabled(false);
        setStatus("Ricerca della TV in corso...", new Color(0xFFD600));
        Discovery.scan(found -> {
            scanButton.setEnabled(true);
            if (found.isEmpty()) {
                setStatus("Nessuna TV trovata: controlla che sia nella stessa rete e che l'app TV sia aperta.",
                        new Color(0xEF5350));
                return;
            }
            Discovery.Found f = found.get(0);
            if (found.size() > 1) {
                Object c = JOptionPane.showInputDialog(frame, "Scegli la TV:", "TV trovate",
                        JOptionPane.QUESTION_MESSAGE, null, found.toArray(), found.get(0));
                if (c == null) {
                    setStatus("Non connesso", new Color(0xBBBBBB));
                    return;
                }
                f = (Discovery.Found) c;
            }
            ipField.setText(f.ip);
            setStatus("Trovata " + f.name + " (" + f.ip + "). Inserisci il PIN (se richiesto) e premi Connetti.",
                    new Color(0x9AD48A));
            pinField.requestFocusInWindow();
        });
    }

    // ------------------------------------------------------------ eventi dalla TV

    private void handleState(Client.State state, String detail) {
        switch (state) {
            case CONNECTING:
                setStatus("Connessione a " + detail + "...", new Color(0xFFD600));
                break;
            case CONNECTED:
                connectedOnce = true;
                setStatus("Connesso a " + detail + " (" + ipField.getText().trim() + ")", new Color(0x9AD48A));
                connectButton.setText("Disconnetti");
                settings.setProperty("ip", ipField.getText().trim());
                settings.setProperty("pin", pinField.getText().trim());
                save();
                break;
            case AUTH_FAILED:
                connectButton.setText("Connetti");
                setStatus("PIN".equals(detail) ? "PIN errato o non richiesto dalla TV." : "Connessione rifiutata: " + detail,
                        new Color(0xEF5350));
                break;
            default:
                connectButton.setText("Connetti");
                setStatus(detail.isEmpty() ? "Disconnesso" : detail, new Color(0xEF5350));
                break;
        }
    }

    private void handleServerMessage(String line) {
        String[] a = line.split("\\|", -1);
        switch (a[0]) {
            case "APP":
                if (a.length >= 3) apps.addElement(new String[]{a[1], decode(a[2], a[1])});
                break;
            case "APPSEND":
                appsInfo.setText(apps.size() + " app sulla TV (doppio clic per avviare)");
                break;
            case "BYE":
                setStatus("Un altro dispositivo si è collegato alla TV.", new Color(0xEF5350));
                break;
            case "TIMER":
                try {
                    int sec = Integer.parseInt(a[1].trim());
                    if (sec > 0) setStatus(status.getText().split("\n")[0] + "\nTimer: la TV si spegne tra "
                            + (sec / 60 + (sec % 60 > 0 ? 1 : 0)) + " min", new Color(0x9AD48A));
                } catch (RuntimeException ignored) {
                    // messaggio non valido
                }
                break;
            case "ERR":
                String code = a.length >= 2 ? a[1] : "";
                String msg = "NOFIELD".equals(code) ? "Sulla TV non c'è un campo di testo selezionato."
                        : "CHARS".equals(code) ? "Alcuni caratteri non possono essere inviati."
                        : "ADB_OFF".equals(code) || "ADB_FAIL".equals(code) ? "Funzione non disponibile: il bridge ADB della TV non è attivo."
                        : "POWER".equals(code) ? "Non sono riuscito a spegnere la TV."
                        : "Errore dalla TV: " + code;
                setStatus(msg, new Color(0xEF5350));
                break;
            default:
                break;
        }
    }

    private static String decode(String b64, String fallback) {
        try {
            String s = new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
            return s.isEmpty() ? fallback : s;
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private void setStatus(String text, Color c) {
        status.setText(text);
        status.setForeground(c);
    }

    // ------------------------------------------------------------ tastiera del PC

    /** Frecce, Invio e Esc del PC comandano la TV quando non si sta scrivendo in una casella. */
    private void installKeyboardShortcuts() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
            if (e.getID() != KeyEvent.KEY_PRESSED || !frame.isActive()) return false;
            Component owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            if (owner instanceof JTextField || owner instanceof JList) return false;
            String cmd = null;
            switch (e.getKeyCode()) {
                case KeyEvent.VK_UP: cmd = "KEY|19"; break;
                case KeyEvent.VK_DOWN: cmd = "KEY|20"; break;
                case KeyEvent.VK_LEFT: cmd = "KEY|21"; break;
                case KeyEvent.VK_RIGHT: cmd = "KEY|22"; break;
                case KeyEvent.VK_ENTER: cmd = "KEY|23"; break;
                case KeyEvent.VK_ESCAPE:
                case KeyEvent.VK_BACK_SPACE: cmd = "BACK"; break;
                case KeyEvent.VK_HOME: cmd = "HOME"; break;
                case KeyEvent.VK_PAGE_UP: cmd = "CHUP"; break;
                case KeyEvent.VK_PAGE_DOWN: cmd = "CHDOWN"; break;
                default: break;
            }
            if (cmd == null) return false;
            client.send(cmd);
            return true;
        });
    }

    // ------------------------------------------------------------ trackpad

    private final class Trackpad extends JPanel {
        private Point last;
        private Point start;
        private long downAt;
        private boolean moved;
        private boolean longSent;
        private final Timer longTimer = new Timer(600, e -> {
            if (!moved && last != null) {
                longSent = true;
                client.send("LONG");
            }
        });

        Trackpad() {
            setBackground(PAD);
            setBorder(BorderFactory.createLineBorder(new Color(0x3C3C4A), 2));
            setFocusable(true);
            longTimer.setRepeats(false);
            MouseAdapter m = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    requestFocusInWindow();
                    if (SwingUtilities.isRightMouseButton(e)) {
                        client.send("BACK");
                        return;
                    }
                    last = e.getPoint();
                    start = e.getPoint();
                    downAt = System.currentTimeMillis();
                    moved = false;
                    longSent = false;
                    longTimer.restart();
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    if (last == null) return;
                    double k = sensitivity.getValue() / 10.0;
                    client.sendMove((e.getX() - last.x) * k, (e.getY() - last.y) * k);
                    last = e.getPoint();
                    if (start.distance(last) > 6) {
                        moved = true;
                        longTimer.stop();
                    }
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    longTimer.stop();
                    if (last != null && !moved && !longSent && System.currentTimeMillis() - downAt < 600) {
                        client.send("CLICK");
                    }
                    last = null;
                }

                @Override
                public void mouseWheelMoved(java.awt.event.MouseWheelEvent e) {
                    client.send("SCROLL|" + (e.getWheelRotation() > 0 ? -1 : 1));
                }
            };
            addMouseListener(m);
            addMouseMotionListener(m);
            addMouseWheelListener(m);
        }

        @Override
        protected void paintComponent(java.awt.Graphics g) {
            super.paintComponent(g);
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setColor(new Color(0x8A8A9A));
            g2.setFont(getFont().deriveFont(Font.BOLD, 18f));
            drawCentered(g2, "Trackpad", -14);
            g2.setColor(new Color(0x6A6A7A));
            g2.setFont(getFont().deriveFont(Font.PLAIN, 13f));
            drawCentered(g2, "Trascina per muovere · clic = tocco · tieni premuto = pressione lunga", 12);
            drawCentered(g2, "Rotellina = scorri · tasto destro = Indietro", 30);
        }

        private void drawCentered(java.awt.Graphics2D g2, String s, int dy) {
            int w = g2.getFontMetrics().stringWidth(s);
            g2.drawString(s, (getWidth() - w) / 2, getHeight() / 2 + dy);
        }
    }

    // ------------------------------------------------------------ impostazioni

    private void load() {
        try (FileInputStream in = new FileInputStream(settingsFile)) {
            settings.load(in);
        } catch (IOException ignored) {
            // prima esecuzione
        }
    }

    private void save() {
        settings.setProperty("sens", String.valueOf(sensitivity.getValue()));
        try (FileOutputStream out = new FileOutputStream(settingsFile)) {
            settings.store(out, "TV Mouse per PC");
        } catch (IOException ignored) {
            // non fondamentale
        }
    }

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        } catch (Exception ignored) {
            // look and feel predefinito
        }
        SwingUtilities.invokeLater(() -> {
            Main m = new Main();
            Runtime.getRuntime().addShutdownHook(new Thread(m::save));
        });
    }
}
