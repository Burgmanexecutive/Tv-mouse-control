package app.tvmouse.desktop;

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
import java.util.function.Consumer;

import javax.swing.SwingUtilities;

/** Cerca la TV nella rete: broadcast UDP, poi scansione TCP delle sottoreti /24. */
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

    private static final int UDP_PORT = 8766;
    private static final String DISCOVER = "TVMOUSE_DISCOVER";
    private static final String HERE = "TVMOUSE_HERE";

    private Discovery() {}

    static void scan(Consumer<List<Found>> callback) {
        Thread t = new Thread(() -> {
            List<Found> result = udpScan();
            if (result.isEmpty()) result = tcpScan();
            List<Found> finalResult = result;
            SwingUtilities.invokeLater(() -> callback.accept(finalResult));
        }, "tvmouse-discovery");
        t.setDaemon(true);
        t.start();
    }

    private static List<Found> udpScan() {
        Map<String, Found> found = new LinkedHashMap<>();
        try (DatagramSocket sock = new DatagramSocket()) {
            sock.setBroadcast(true);
            byte[] msg = DISCOVER.getBytes(StandardCharsets.US_ASCII);
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
                            sock.send(new DatagramPacket(msg, msg.length, target, UDP_PORT));
                        } catch (IOException ignored) {
                            // interfaccia senza broadcast
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
                if (a.length >= 2 && a[0].equals(HERE)) {
                    String ip = p.getAddress().getHostAddress();
                    found.putIfAbsent(ip, new Found(ip, a[1]));
                }
            }
        } catch (IOException ignored) {
            // rete non disponibile
        }
        return new ArrayList<>(found.values());
    }

    private static List<InetAddress> broadcastTargets() {
        List<InetAddress> targets = new ArrayList<>();
        try {
            targets.add(InetAddress.getByName("255.255.255.255"));
        } catch (IOException ignored) {
            // indirizzo numerico
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
            // solo broadcast generico
        }
        return targets;
    }

    private static List<Found> tcpScan() {
        List<Found> found = Collections.synchronizedList(new ArrayList<>());
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
                String ip = prefix + i;
                if (own.contains(ip)) continue;
                jobs.add(() -> {
                    try (Socket s = new Socket()) {
                        s.connect(new InetSocketAddress(ip, Client.TCP_PORT), 300);
                        found.add(new Found(ip, "TV"));
                    } catch (IOException ignored) {
                        // nessuno in ascolto
                    }
                    return null;
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
