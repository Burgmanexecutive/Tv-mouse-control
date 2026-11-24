package app.tvmouse.phone;

import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import app.tvmouse.common.SecureChannel;

/** Connessione cifrata verso la TV: autenticazione con PIN, invio comandi, PING periodico. */
final class RemoteClient {

    enum State { DISCONNECTED, CONNECTING, CONNECTED, AUTH_FAILED }

    interface Listener {
        /** Sempre sul thread principale. */
        void onState(State state, String detail);

        /** Messaggi inviati dalla TV (ERR|..., APP|..., MAC|..., BYE|...). Thread principale. */
        void onServerMessage(String line);
    }

    private final String deviceName;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScheduledThreadPoolExecutor io = new ScheduledThreadPoolExecutor(1, new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "tvmouse-io");
            t.setDaemon(true);
            return t;
        }
    });

    private final Object moveLock = new Object();
    private float pendingDx;
    private float pendingDy;
    private boolean flushScheduled;

    private volatile Socket socket;
    private volatile SecureChannel channel;
    private volatile boolean authed;
    private volatile int generation;
    private volatile long lastRx;

    RemoteClient(String deviceName, Listener listener) {
        this.deviceName = deviceName;
        this.listener = listener;
        io.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                if (!authed) return;
                if (System.currentTimeMillis() - lastRx > 25000) {
                    closeSocket(); // la TV non risponde piu': il lettore segnala la disconnessione
                } else {
                    writeNow("PING");
                }
            }
        }, 10, 10, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------ API

    /** pin puo' essere vuoto se la TV non lo richiede. */
    void connect(final String host, final String pin) {
        final int gen;
        synchronized (this) {
            generation++;
            gen = generation;
            closeSocket();
        }
        post(gen, State.CONNECTING, host);
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                runConnection(host, pin, gen);
            }
        }, "tvmouse-reader");
        t.setDaemon(true);
        t.start();
    }

    synchronized void disconnect() {
        generation++;
        closeSocket();
    }

    void send(final String line) {
        if (!authed) return;
        io.execute(new Runnable() {
            @Override
            public void run() {
                writeNow(line);
            }
        });
    }

    /** Accumula i movimenti e li invia a blocchi (circa ogni 10 ms) come interi. */
    void sendMove(float dx, float dy) {
        if (!authed) return;
        synchronized (moveLock) {
            pendingDx += dx;
            pendingDy += dy;
            if (flushScheduled) return;
            flushScheduled = true;
        }
        io.schedule(new Runnable() {
            @Override
            public void run() {
                flushMove();
            }
        }, 10, TimeUnit.MILLISECONDS);
    }

    // ------------------------------------------------------------ interni

    private void flushMove() {
        int ix;
        int iy;
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
            s.connect(new InetSocketAddress(host, Protocol.TCP_PORT), 3000);
            s.setTcpNoDelay(true);
            s.setSoTimeout(12000); // handshake e autenticazione (la TV puo' rallentare dopo un PIN errato)
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

    private void post(final int gen, final State state, final String detail) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (gen == generation) listener.onState(state, detail);
            }
        });
    }

    private void postMessage(final int gen, final String line) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (gen == generation) listener.onServerMessage(line);
            }
        });
    }
}
