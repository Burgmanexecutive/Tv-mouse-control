package app.tvmouse.tv;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.net.ConnectException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import dadb.AdbKeyPair;
import dadb.AdbShellResponse;
import dadb.Dadb;

/**
 * Ponte verso l'ADB della TV stessa (127.0.0.1). Serve per i tasti che un'app normale
 * non puo' simulare: canale, spegnimento, frecce, tasti numerici, riproduzione.
 *
 * Richiede che sulla TV sia attivo il debug ADB via rete (Opzioni sviluppatore).
 */
final class AdbBridge {

    interface Result {
        /** code e' null se ok, altrimenti ADB_OFF oppure ADB_FAIL. */
        void done(boolean ok, String code);
    }

    interface Output {
        /** output e' il testo prodotto dal comando (vuoto se fallito). */
        void done(boolean ok, String output);
    }

    private static AdbBridge instance;

    static synchronized AdbBridge get(Context c) {
        if (instance == null) instance = new AdbBridge(c.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private final ExecutorService queue = Executors.newSingleThreadExecutor();
    private final ExecutorService worker = Executors.newCachedThreadPool();
    private final AtomicInteger pending = new AtomicInteger();
    private volatile Dadb dadb;
    private volatile int dadbPort;
    private volatile boolean ready;
    private volatile String status = "non ancora provato";

    private AdbBridge(Context ctx) {
        this.ctx = ctx;
    }

    /** True se l'ultimo comando ADB e' andato a buon fine. */
    boolean isReady() {
        return ready;
    }

    String status() {
        return status;
    }

    /** Se ci sono gia' diversi tasti in coda (tasto tenuto premuto) quelli in eccesso vengono scartati. */
    void sendKey(int keyCode, Result r) {
        if (pending.get() >= 4) return;
        exec("input keyevent " + keyCode, r, null);
    }

    /** Esegue un comando di shell sulla TV (usare solo con testo già controllato). */
    void shell(String cmd, Result r) {
        exec(cmd, r, null);
    }

    void shellOutput(String cmd, Output o) {
        exec(cmd, null, o);
    }

    void test(Result r) {
        exec("echo ok", r, null);
    }

    private void exec(final String cmd, final Result r, final Output o) {
        pending.incrementAndGet();
        queue.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    runOne(cmd, r, o);
                } finally {
                    pending.decrementAndGet();
                }
            }
        });
    }

    private void runOne(final String cmd, Result r, Output o) {
        Future<String> f = worker.submit(new Callable<String>() {
            @Override
            public String call() throws Exception {
                return runShell(cmd);
            }
        });
        String code = null;
        String out = "";
        try {
            out = f.get(12, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            closeDadb();
            code = "ADB_FAIL";
        } catch (ExecutionException e) {
            code = classify(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            code = "ADB_FAIL";
        }
        if (code == null) {
            ready = true;
            status = "collegato";
        } else {
            ready = false;
            status = code.equals("ADB_OFF")
                    ? "non attivo sulla TV (abilita il debug di rete)"
                    : "non risponde (conferma la richiesta di debug sulla TV)";
        }
        if (r != null) r.done(code == null, code);
        if (o != null) o.done(code == null, code == null ? out : "");
    }

    private String runShell(String cmd) throws Exception {
        for (int attempt = 0; ; attempt++) {
            try {
                Dadb d = connect();
                AdbShellResponse resp = d.shell(cmd);
                if (resp.getExitCode() != 0) {
                    throw new IOException("exit " + resp.getExitCode());
                }
                String o = resp.getOutput();
                return o == null ? "" : o;
            } catch (Exception e) {
                closeDadb();
                if (attempt >= 1) throw e;
            }
        }
    }

    private Dadb connect() {
        int port = Prefs.adbPort(ctx);
        Dadb d = dadb;
        if (d != null && dadbPort == port) return d;
        closeDadb();

        File priv = new File(ctx.getFilesDir(), "adbkey");
        File pub = new File(ctx.getFilesDir(), "adbkey.pub");
        if (!priv.exists() || !pub.exists()) {
            AdbKeyPair.generate(priv, pub);
        }
        AdbKeyPair keyPair = AdbKeyPair.read(priv, pub);
        d = Dadb.create("127.0.0.1", port, keyPair);
        dadb = d;
        dadbPort = port;
        return d;
    }

    private void closeDadb() {
        ready = false;
        Dadb d = dadb;
        dadb = null;
        if (d != null) {
            try {
                d.close();
            } catch (Exception ignored) {
                // gia' chiuso
            }
        }
    }

    private static String classify(Throwable t) {
        for (Throwable x = t; x != null; x = x.getCause()) {
            if (x instanceof ConnectException) return "ADB_OFF";
            String m = x.getMessage();
            if (m != null && m.toLowerCase().contains("refused")) return "ADB_OFF";
        }
        return "ADB_FAIL";
    }
}
