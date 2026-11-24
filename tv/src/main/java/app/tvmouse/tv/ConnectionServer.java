package app.tvmouse.tv;

import android.content.Context;
import android.os.Build;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;

import app.tvmouse.common.SecureChannel;

/**
 * Server della TV: accetta una connessione TCP alla volta dal telefono, cifrata (vedi SecureChannel)
 * e protetta dal PIN (se attivo), e risponde ai messaggi UDP di ricerca inviati dal telefono.
 */
final class ConnectionServer {

    interface Handler {
        /** Chiamato sul thread della connessione dopo l'autenticazione. */
        void onSessionStart(Session session);

        /** Chiamato sul thread della connessione: non bloccare. */
        void onCommand(Session session, String[] args);
    }

    private final Context ctx;
    private final Handler handler;
    private volatile boolean running;
    private volatile ServerSocket serverSocket;
    private volatile DatagramSocket udpSocket;
    private volatile Session active;
    private int failedAttempts; // protetto da "this"

    ConnectionServer(Context ctx, Handler handler) {
        this.ctx = ctx.getApplicationContext();
        this.handler = handler;
    }

    void start() {
        running = true;
        Thread tcp = new Thread(this::tcpLoop, "tvmouse-tcp");
        tcp.setDaemon(true);
        tcp.start();
        Thread udp = new Thread(this::udpLoop, "tvmouse-udp");
        udp.setDaemon(true);
        udp.start();
    }

    void stop() {
        running = false;
        closeQuietly(serverSocket);
        closeQuietly(udpSocket);
        Session a = active;
        if (a != null) a.close();
    }

    // ---------------------------------------------------------------- TCP

    private void tcpLoop() {
        while (running) {
            try (ServerSocket ss = new ServerSocket()) {
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(Protocol.TCP_PORT));
                serverSocket = ss;
                while (running) {
                    Socket s = ss.accept();
                    Thread t = new Thread(new Session(s), "tvmouse-session");
                    t.setDaemon(true);
                    t.start();
                }
            } catch (IOException e) {
                if (!running) return;
                sleep(3000);
            }
        }
    }

    final class Session implements Runnable {
        private final Socket socket;
        private volatile SecureChannel channel;

        Session(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(15000); // handshake e autenticazione entro 15 s
                InputStream in = socket.getInputStream();
                OutputStream os = socket.getOutputStream();

                String pin = Prefs.requirePin(ctx) ? Prefs.pin(ctx) : "";
                SecureChannel ch = SecureChannel.acceptAsServer(in, os, pin);

                String first;
                try {
                    first = ch.receive();
                } catch (SecureChannel.AuthFailedException e) {
                    first = null; // chiavi diverse: PIN sbagliato
                }
                if (first == null || !first.startsWith("AUTH")) {
                    ch.sendReject("PIN"); // prima rispondo, poi rallento chi riprova
                    penalize();
                    return;
                }
                resetFailures();

                channel = ch;
                Session old = active;
                active = this;
                if (old != null && old != this) {
                    old.send("BYE|REPLACED");
                    old.close();
                }

                socket.setSoTimeout(30000); // il telefono invia PING ogni 10 s
                send("OK|" + clean(Build.MODEL));
                handler.onSessionStart(this);

                String line;
                while (running && (line = ch.receive()) != null) {
                    if (line.isEmpty()) continue;
                    String[] args = line.split("\\|", -1);
                    if (args[0].equals("PING")) {
                        send("PONG");
                        continue;
                    }
                    try {
                        handler.onCommand(this, args);
                    } catch (RuntimeException ignored) {
                        // comando malformato: lo ignoro e continuo
                    }
                }
            } catch (IOException ignored) {
                // connessione chiusa, scaduta o handshake non valido
            } finally {
                close();
                if (active == this) active = null;
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
            closeQuietly(socket);
        }
    }

    /** Rallenta chi sbaglia il PIN: 1 s, 2 s, ... fino a 10 s per tentativo. */
    private void penalize() {
        int n;
        synchronized (this) {
            failedAttempts++;
            n = Math.min(failedAttempts, 10);
        }
        sleep(n * 1000L);
    }

    private synchronized void resetFailures() {
        failedAttempts = 0;
    }

    // ---------------------------------------------------------------- UDP

    private void udpLoop() {
        while (running) {
            try (DatagramSocket ds = new DatagramSocket((SocketAddress) null)) {
                ds.setReuseAddress(true);
                ds.setBroadcast(true);
                ds.bind(new InetSocketAddress(Protocol.UDP_PORT));
                udpSocket = ds;
                byte[] buf = new byte[128];
                while (running) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    ds.receive(p);
                    String msg = new String(p.getData(), 0, p.getLength(), StandardCharsets.US_ASCII).trim();
                    if (msg.equals(Protocol.DISCOVER)) {
                        byte[] reply = (Protocol.HERE + "|" + clean(Build.MODEL) + "|" + Protocol.TCP_PORT)
                                .getBytes(StandardCharsets.US_ASCII);
                        ds.send(new DatagramPacket(reply, reply.length, p.getAddress(), p.getPort()));
                    }
                }
            } catch (IOException e) {
                if (!running) return;
                sleep(3000);
            }
        }
    }

    // ------------------------------------------------------------- utilita'

    static String clean(String s) {
        if (s == null) return "TV";
        String t = s.replaceAll("[^A-Za-z0-9 _.-]", "?");
        if (t.length() > 30) t = t.substring(0, 30);
        return t.isEmpty() ? "TV" : t;
    }

    private static void closeQuietly(Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
            // niente da fare
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
