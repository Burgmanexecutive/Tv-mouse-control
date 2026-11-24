package app.tvmouse.phone;

import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Cerca la TV nella rete locale, anche quando la rete e' l'hotspot del telefono.
 * 1) broadcast UDP su ogni interfaccia (compresa quella dell'hotspot)
 * 2) se non risponde nessuno, scansione TCP delle sottoreti /24 vicine
 */
final class Discovery {

    static final class Found {
        final String ip;
        final String name;

        Found(String ip, String name) {
            this.ip = ip;
            this.name = name;
        }

        @Override
        public String toString() {
            return name + " (" + ip + ")";
        }
    }

    interface Callback {
        /** Sul thread principale. Lista vuota se non e' stata trovata nessuna TV. */
        void onResult(List<Found> found);
    }

    private Discovery() {}

    static void scan(final Callback callback) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                List<Found> result = udpScan();
                if (result.isEmpty()) result = tcpScan();
                final List<Found> finalResult = result;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onResult(finalResult);
                    }
                });
            }
        }, "tvmouse-discovery");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------ UDP

    private static List<Found> udpScan() {
        Map<String, Found> found = new LinkedHashMap<>();
        try (DatagramSocket sock = new DatagramSocket()) {
            sock.setBroadcast(true);
            byte[] msg = Protocol.DISCOVER.getBytes(StandardCharsets.US_ASCII);
            List<InetAddress> targets = broadcastTargets();
            long end = System.currentTimeMillis() + 2500;
            long nextSend = 0;
            int sent = 0;
            byte[] buf = new byte[256];
            sock.setSoTimeout(200);
            while (System.currentTimeMillis() < end) {
                long now = System.currentTimeMillis();
                if (sent < 3 && now >= nextSend) {
                    for (InetAddress target : targets) {
                        try {
                            sock.send(new DatagramPacket(msg, msg.length, target, Protocol.UDP_PORT));
                        } catch (IOException ignored) {
                            // questa interfaccia non permette il broadcast
                        }
                    }
                    sent++;
                    nextSend = now + 600;
                }
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    sock.receive(p);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                String[] a = new String(p.getData(), 0, p.getLength(), StandardCharsets.US_ASCII).split("\\|");
                if (a.length >= 2 && a[0].equals(Protocol.HERE)) {
                    String ip = p.getAddress().getHostAddress();
                    if (!found.containsKey(ip)) found.put(ip, new Found(ip, a[1]));
                }
            }
        } catch (IOException ignored) {
            // rete non disponibile
        }
        return new ArrayList<>(found.values());
    }

    static List<InetAddress> broadcastTargets() {
        List<InetAddress> targets = new ArrayList<>();
        try {
            targets.add(InetAddress.getByName("255.255.255.255"));
        } catch (UnknownHostException ignored) {
            // impossibile, e' un indirizzo numerico
        }
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress b = ia.getBroadcast();
                    if (b != null && !targets.contains(b)) targets.add(b);
                }
            }
        } catch (SocketException ignored) {
            // uso solo il broadcast generico
        }
        return targets;
    }

    // ------------------------------------------------------------ TCP

    private static List<Found> tcpScan() {
        final List<Found> found = Collections.synchronizedList(new ArrayList<Found>());
        List<String> prefixes = new ArrayList<>();
        List<String> own = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        String ip = a.getHostAddress();
                        own.add(ip);
                        String prefix = ip.substring(0, ip.lastIndexOf('.') + 1);
                        if (!prefixes.contains(prefix)) prefixes.add(prefix);
                    }
                }
            }
        } catch (SocketException ignored) {
            return new ArrayList<>(found);
        }

        List<Callable<Void>> jobs = new ArrayList<>();
        for (String prefix : prefixes) {
            for (int i = 1; i <= 254; i++) {
                final String ip = prefix + i;
                if (own.contains(ip)) continue;
                jobs.add(new Callable<Void>() {
                    @Override
                    public Void call() {
                        try (Socket s = new Socket()) {
                            s.connect(new InetSocketAddress(ip, Protocol.TCP_PORT), 300);
                            found.add(new Found(ip, "TV"));
                        } catch (IOException ignored) {
                            // nessuno in ascolto su questo indirizzo
                        }
                        return null;
                    }
                });
            }
        }

        ExecutorService pool = Executors.newFixedThreadPool(48);
        try {
            pool.invokeAll(jobs, 15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdownNow();
        }
        return new ArrayList<>(found);
    }
}
