package app.tvmouse.desktop;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;

import app.tvmouse.common.SecureChannel;

/**
 * Ricevitore per PC e notebook: l'app TV Mouse del telefono controlla questo computer
 * (mouse, tastiera, testo, volume, spegnimento) con lo stesso protocollo cifrato usato per la TV.
 */
public final class Receiver {

    private static final int TCP_PORT = 8765;
    private static final int UDP_PORT = 8766;
    private static final int MAX_TIMER_MINUTES = 720;
    private static final int POWER_DELAY_SECONDS = 30;

    private final File settingsFile = new File(System.getProperty("user.home"), ".tvmouse-receiver.properties");
    private final Properties settings = new Properties();
    private final Injector in;
    private final Programs programs = new Programs();
    private final String hostName;

    private volatile boolean requirePin;
    private volatile String pin;
    private volatile Session active;
    private int failedAttempts;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "tvmouse-timer");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> shutdownTask;
    private volatile long shutdownAt;

    // interfaccia
    private final JFrame frame = new JFrame("TV Mouse - ricevitore PC");
    private final JLabel ipLabel = new JLabel();
    private final JLabel pinLabel = new JLabel();
    private final JLabel statusLabel = new JLabel("In attesa del telefono...");
    private final JLabel timerLabel = new JLabel(" ");
    private final JButton cancelTimer = new JButton("Annulla spegnimento");
    private final JButton pinToggle = new JButton();

    private Receiver(Injector injector) {
        this.in = injector;
        load();
        String h = "PC";
        try {
            h = InetAddress.getLocalHost().getHostName();
        } catch (IOException ignored) {
            // nome generico
        }
        hostName = clean(h);
        buildUi();
        start();
    }

    // ------------------------------------------------------------ interfaccia

    private void buildUi() {
        JPanel p = new JPanel(new GridLayout(0, 1, 0, 8));
        p.setBackground(new Color(0x303030));
        p.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
        for (JLabel l : new JLabel[]{ipLabel, pinLabel, statusLabel, timerLabel}) {
            l.setForeground(Color.WHITE);
            l.setFont(l.getFont().deriveFont(Font.PLAIN, 16f));
        }
        pinLabel.setFont(pinLabel.getFont().deriveFont(Font.BOLD, 26f));
        JLabel title = new JLabel("TV Mouse - ricevitore PC");
        title.setForeground(Color.WHITE);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 20f));
        p.add(title);
        p.add(ipLabel);
        p.add(pinLabel);
        p.add(statusLabel);
        p.add(timerLabel);

        JButton newPin = button("Genera un nuovo PIN", new Color(0x1565C0));
        newPin.addActionListener(e -> {
            pin = randomPin();
            save();
            refresh();
        });
        pinToggle.setBackground(new Color(0x1565C0));
        style(pinToggle);
        pinToggle.addActionListener(e -> {
            requirePin = !requirePin;
            save();
            refresh();
        });
        cancelTimer.setBackground(new Color(0xC62828));
        style(cancelTimer);
        cancelTimer.setVisible(false);
        cancelTimer.addActionListener(e -> cancelShutdown());
        p.add(newPin);
        p.add(pinToggle);
        p.add(cancelTimer);

        JLabel note = new JLabel("<html><body style='width:330px'>Sul telefono apri TV Mouse, premi \"TV\", "
                + "poi \"Aggiungi o cerca una TV\": il computer comparirà nell'elenco. "
                + "Se il firewall lo chiede, consenti a Java l'accesso alla rete privata.</body></html>");
        note.setForeground(new Color(0xBBBBBB));
        p.add(note);

        frame.setContentPane(p);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.pack();
        frame.setMinimumSize(frame.getSize());
        frame.setLocationByPlatform(true);
        frame.setVisible(true);
        new Timer(1000, e -> refresh()).start();
        refresh();
    }

    private JButton button(String text, Color bg) {
        JButton b = new JButton(text);
        b.setBackground(bg);
        style(b);
        return b;
    }

    private void style(JButton b) {
        b.setForeground(Color.WHITE);
        b.setOpaque(true);
        b.setFocusPainted(false);
        b.setFont(b.getFont().deriveFont(Font.PLAIN, 15f));
        b.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
    }

    private void refresh() {
        List<String> ips = localIps();
        ipLabel.setText("<html>Indirizzo IP del computer: <b><font color='#FFEA00'>"
                + (ips.isEmpty() ? "nessuna rete" : String.join(", ", ips)) + "</font></b></html>");
        pinLabel.setText(requirePin ? "PIN: " + pin : "PIN: non richiesto");
        pinToggle.setText(requirePin ? "PIN richiesto: SÌ (clic per disattivarlo)" : "PIN richiesto: NO (clic per attivarlo)");
        long left = shutdownAt - System.currentTimeMillis();
        if (shutdownAt > 0 && left > 0) {
            long s = left / 1000;
            timerLabel.setText("Spegnimento del computer tra " + (s / 60) + " min " + (s % 60) + " s");
            timerLabel.setForeground(new Color(0xFF8A80));
            cancelTimer.setVisible(true);
        } else {
            timerLabel.setText(" ");
            cancelTimer.setVisible(false);
        }
    }

    private void setStatus(String s) {
        SwingUtilities.invokeLater(() -> statusLabel.setText(s));
    }

    // ------------------------------------------------------------ server

    private void start() {
        startThread("tvmouse-tcp", this::tcpLoop);
        startThread("tvmouse-udp", this::udpLoop);
    }

    private static void startThread(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }

    private void tcpLoop() {
        while (true) {
            try (ServerSocket ss = new ServerSocket()) {
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(TCP_PORT));
                while (true) {
                    Socket s = ss.accept();
                    startThread("tvmouse-session", new Session(s));
                }
            } catch (IOException e) {
                setStatus("Porta " + TCP_PORT + " occupata: è già aperto un altro ricevitore?");
                sleep(3000);
            }
        }
    }

    private void udpLoop() {
        while (true) {
            try (DatagramSocket ds = new DatagramSocket((SocketAddress) null)) {
                ds.setReuseAddress(true);
                ds.setBroadcast(true);
                ds.bind(new InetSocketAddress(UDP_PORT));
                byte[] buf = new byte[128];
                while (true) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    ds.receive(p);
                    String msg = new String(p.getData(), 0, p.getLength(), StandardCharsets.US_ASCII).trim();
                    if (msg.equals("TVMOUSE_DISCOVER")) {
                        byte[] reply = ("TVMOUSE_HERE|" + hostName + "|" + TCP_PORT).getBytes(StandardCharsets.US_ASCII);
                        ds.send(new DatagramPacket(reply, reply.length, p.getAddress(), p.getPort()));
                    }
                }
            } catch (IOException e) {
                sleep(3000);
            }
        }
    }

    private final class Session implements Runnable {
        private final Socket socket;
        private volatile SecureChannel channel;

        Session(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(15000);
                InputStream is = socket.getInputStream();
                OutputStream os = socket.getOutputStream();
                SecureChannel ch = SecureChannel.acceptAsServer(is, os, requirePin ? pin : "");
                String first;
                try {
                    first = ch.receive();
                } catch (SecureChannel.AuthFailedException e) {
                    first = null; // PIN sbagliato
                }
                if (first == null || !first.startsWith("AUTH")) {
                    ch.sendReject("PIN");
                    penalize();
                    return;
                }
                synchronized (Receiver.this) {
                    failedAttempts = 0;
                }
                channel = ch;
                Session old = active;
                active = this;
                if (old != null && old != this) {
                    old.send("BYE|REPLACED");
                    old.close();
                }
                socket.setSoTimeout(30000);
                send("OK|" + hostName);
                send("TIMER|" + remainingSeconds());
                setStatus("Telefono collegato: " + clean(first.length() > 5 ? first.substring(5) : "telefono"));

                String line;
                while ((line = ch.receive()) != null) {
                    if (line.isEmpty()) continue;
                    String[] a = line.split("\\|", -1);
                    if (a[0].equals("PING")) {
                        send("PONG");
                        continue;
                    }
                    try {
                        command(this, a);
                    } catch (RuntimeException ignored) {
                        // comando non valido: lo ignoro
                    }
                }
            } catch (IOException ignored) {
                // connessione chiusa
            } finally {
                close();
                if (active == this) {
                    active = null;
                    setStatus("In attesa del telefono...");
                }
            }
        }

        synchronized void send(String line) {
            SecureChannel ch = channel;
            if (ch == null) return;
            try {
                ch.send(line);
            } catch (IOException e) {
                close();
            }
        }

        void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // gia' chiusa
            }
        }
    }

    private void penalize() {
        int n;
        synchronized (this) {
            failedAttempts++;
            n = Math.min(failedAttempts, 10);
        }
        sleep(n * 1000L);
    }

    // ------------------------------------------------------------ comandi

    private void command(Session s, String[] a) {
        switch (a[0]) {
            case "MOVE":
                if (a.length >= 3) in.move(Integer.parseInt(a[1]), Integer.parseInt(a[2]));
                break;
            case "CLICK":
                in.leftClick();
                break;
            case "LONG":
                in.rightClick();
                break;
            case "SCROLL":
                if (a.length >= 2) in.scroll(Integer.parseInt(a[1]) > 0 ? 1 : -1);
                break;
            case "BACK":
                in.back();
                break;
            case "HOME":
                in.home();
                break;
            case "VOLUP":
                in.volume(1);
                break;
            case "VOLDOWN":
                in.volume(-1);
                break;
            case "MUTE":
                in.mute();
                break;
            case "CHUP":
                in.key(KeyEvent.VK_PAGE_UP);
                break;
            case "CHDOWN":
                in.key(KeyEvent.VK_PAGE_DOWN);
                break;
            case "ENTER":
                in.key(KeyEvent.VK_ENTER);
                break;
            case "DEL":
                if (a.length >= 2) in.backspace(Math.max(0, Math.min(500, Integer.parseInt(a[1]))));
                break;
            case "TEXT":
                if (a.length >= 2) {
                    String text = new String(Base64.getDecoder().decode(a[1]), StandardCharsets.UTF_8);
                    if (text.length() <= 400) in.typeText(text);
                }
                break;
            case "KEY":
                if (a.length >= 2) key(Integer.parseInt(a[1]));
                break;
            case "POWER":
                scheduleShutdown(POWER_DELAY_SECONDS);
                break;
            case "TIMER":
                if (a.length >= 2) {
                    int m = Math.max(0, Math.min(MAX_TIMER_MINUTES, Integer.parseInt(a[1].trim())));
                    if (m == 0) cancelShutdown();
                    else scheduleShutdown(m * 60);
                }
                break;
            case "APPS": {
                for (Programs.Item it : programs.refresh()) {
                    String label = it.name.length() > 40 ? it.name.substring(0, 40) : it.name;
                    s.send("APP|" + it.id + "|" + Base64.getEncoder().encodeToString(label.getBytes(StandardCharsets.UTF_8)));
                }
                s.send("APPSEND");
                break;
            }
            case "LAUNCH":
                if (a.length >= 2) programs.launch(a[1]);
                break;
            default:
                break;
        }
    }

    /** Codici tasto Android (come inviati dal telefono) -> tastiera del computer. */
    private void key(int code) {
        switch (code) {
            case 19: in.key(KeyEvent.VK_UP); break;
            case 20: in.key(KeyEvent.VK_DOWN); break;
            case 21: in.key(KeyEvent.VK_LEFT); break;
            case 22: in.key(KeyEvent.VK_RIGHT); break;
            case 23: in.key(KeyEvent.VK_ENTER); break;
            case 82: in.key(KeyEvent.VK_CONTEXT_MENU); break;
            case 85: case 126: case 127: in.media("playpause"); break;
            case 87: case 90: in.media("next"); break;
            case 88: case 89: in.media("previous"); break;
            default:
                if (code >= 7 && code <= 16) in.key(KeyEvent.VK_0 + (code - 7));
                break;
        }
    }

    // ------------------------------------------------------------ spegnimento

    private synchronized void scheduleShutdown(int seconds) {
        if (shutdownTask != null) shutdownTask.cancel(false);
        shutdownAt = System.currentTimeMillis() + seconds * 1000L;
        shutdownTask = scheduler.schedule(in::shutdownNow, seconds, TimeUnit.SECONDS);
        notifyPhone();
    }

    private synchronized void cancelShutdown() {
        if (shutdownTask != null) shutdownTask.cancel(false);
        shutdownTask = null;
        shutdownAt = 0;
        notifyPhone();
    }

    private int remainingSeconds() {
        long left = shutdownAt - System.currentTimeMillis();
        return shutdownAt > 0 && left > 0 ? (int) ((left + 999) / 1000) : 0;
    }

    private void notifyPhone() {
        Session s = active;
        if (s != null) s.send("TIMER|" + remainingSeconds());
    }

    // ------------------------------------------------------------ impostazioni e utilita'

    private void load() {
        try (FileInputStream f = new FileInputStream(settingsFile)) {
            settings.load(f);
        } catch (IOException ignored) {
            // prima esecuzione
        }
        pin = settings.getProperty("pin", "");
        if (!pin.matches("\\d{6}")) pin = randomPin();
        requirePin = !"false".equals(settings.getProperty("requirePin"));
        save();
    }

    private void save() {
        settings.setProperty("pin", pin);
        settings.setProperty("requirePin", String.valueOf(requirePin));
        try (FileOutputStream f = new FileOutputStream(settingsFile)) {
            settings.store(f, "TV Mouse - ricevitore PC");
        } catch (IOException ignored) {
            // non fondamentale
        }
    }

    private static String randomPin() {
        return String.format("%06d", new SecureRandom().nextInt(1000000));
    }

    private static List<String> localIps() {
        List<String> ips = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()) {
                        ips.add(a.getHostAddress());
                    }
                }
            }
        } catch (IOException ignored) {
            // nessuna rete
        }
        return ips;
    }

    private static String clean(String s) {
        String t = s.replaceAll("[^A-Za-z0-9 _.-]", "?");
        if (t.length() > 30) t = t.substring(0, 30);
        return t.isEmpty() ? "PC" : t;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        } catch (Exception ignored) {
            // look and feel predefinito
        }
        SwingUtilities.invokeLater(() -> {
            try {
                new Receiver(new Injector());
            } catch (java.awt.AWTException | UnsupportedOperationException e) {
                JOptionPane.showMessageDialog(null, "Questo sistema non permette di controllare il mouse: " + e.getMessage(),
                        "TV Mouse", JOptionPane.ERROR_MESSAGE);
            }
        });
    }
}
