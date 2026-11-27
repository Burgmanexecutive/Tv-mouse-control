package app.tvmouse.desktop;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.swing.SwingUtilities;

import app.tvmouse.common.SecureChannel;

/** Connessione cifrata verso la TV (stesso protocollo dell'app del telefono). */
final class Client {

    enum State { DISCONNECTED, CONNECTING, CONNECTED, AUTH_FAILED }

    interface Listener {
        /** Sempre sul thread dell'interfaccia. */
        void onState(State state, String detail);

        void onServerMessage(String line);
    }

    static final int TCP_PORT = 8765;

    private final String deviceName;
    private final Listener listener;
    private final ScheduledThreadPoolExecutor io = new ScheduledThreadPoolExecutor(1, r -> {
        Thread t = new Thread(r, "tvmouse-io");
        t.setDaemon(true);
        return t;
    });

    private final Object moveLock = new Object();
    private double pendingDx;
    private double pendingDy;
    private boolean flushScheduled;

    private volatile Socket socket;
    private volatile SecureChannel channel;
    private volatile boolean authed;
    private volatile int generation;
    private volatile long lastRx;

    Client(String deviceName, Listener listener) {
        this.deviceName = deviceName;
        this.listener = listener;
        io.scheduleWithFixedDelay(() -> {
            if (!authed) return;
            if (System.currentTimeMillis() - lastRx > 25000) {
                closeSocket();
            } else {
                writeNow("PING");
            }
        }, 10, 10, TimeUnit.SECONDS);
    }

    boolean isConnected() {
        return authed;
    }

    void connect(String host, String pin) {
        final int gen;
        synchronized (this) {
            generation++;
            gen = generation;
            closeSocket();
        }
        post(gen, State.CONNECTING, host);
        Thread t = new Thread(() -> runConnection(host, pin, gen), "tvmouse-reader");
        t.setDaemon(true);
        t.start();
    }

    synchronized void disconnect() {
        generation++;
        closeSocket();
    }

    void send(String line) {
        if (!authed) return;
        io.execute(() -> writeNow(line));
    }

    void sendMove(double dx, double dy) {
        if (!authed) return;
        synchronized (moveLock) {
            pendingDx += dx;
            pendingDy += dy;
            if (flushScheduled) return;
            flushScheduled = true;
        }
        io.schedule(this::flushMove, 10, TimeUnit.MILLISECONDS);
    }

    private void flushMove() {
        long ix;
        long iy;
        synchronized (moveLock) {
            ix = Math.round(pendingDx);
            iy = Math.round(pendingDy);
            pendingDx -= ix;
            pendingDy -= iy;
            flushScheduled = false;
        }
        if (ix != 0 || iy != 0) writeNow("MOVE|" + ix + "|" + iy);
    }

    private void runConnection(String host, String pin, int gen) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, TCP_PORT), 3000);
            s.setTcpNoDelay(true);
            s.setSoTimeout(12000);
            if (gen != generation) {
                s.close();
                return;
            }
            socket = s;

            SecureChannel ch = SecureChannel.connectAsClient(s.getInputStream(), s.getOutputStream(), pin);
            ch.send("AUTH|" + deviceName);
            String line = ch.receive();
            if (line == null) throw new IOException("chiusa");
            if (!line.startsWith("OK")) {
                post(gen, State.AUTH_FAILED, "PIN");
                return;
            }
            String tvName = line.length() > 3 ? line.substring(3) : "TV";
            s.setSoTimeout(0);
            lastRx = System.currentTimeMillis();
            channel = ch;
            authed = true;
            post(gen, State.CONNECTED, tvName);

            while ((line = ch.receive()) != null) {
                lastRx = System.currentTimeMillis();
                if (line.isEmpty() || line.equals("PONG")) continue;
                postMessage(gen, line);
            }
            post(gen, State.DISCONNECTED, "");
        } catch (SecureChannel.RejectedException e) {
            post(gen, State.AUTH_FAILED, e.code);
        } catch (SecureChannel.AuthFailedException e) {
            post(gen, State.AUTH_FAILED, "PIN");
        } catch (SocketTimeoutException e) {
            post(gen, State.DISCONNECTED, "Nessuna risposta dalla TV");
        } catch (IOException e) {
            post(gen, State.DISCONNECTED, "TV non raggiungibile");
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
                // gia' chiuso
            }
            synchronized (this) {
                if (gen == generation) closeSocket();
            }
        }
    }

    private void writeNow(String line) {
        SecureChannel ch = channel;
        if (ch == null) return;
        try {
            ch.send(line);
        } catch (IOException e) {
            closeSocket();
        }
    }

    private void closeSocket() {
        authed = false;
        channel = null;
        Socket s = socket;
        socket = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // gia' chiuso
            }
        }
    }

    private void post(int gen, State state, String detail) {
        SwingUtilities.invokeLater(() -> {
            if (gen == generation) listener.onState(state, detail);
        });
    }

    private void postMessage(int gen, String line) {
        SwingUtilities.invokeLater(() -> {
            if (gen == generation) listener.onServerMessage(line);
        });
    }
}
