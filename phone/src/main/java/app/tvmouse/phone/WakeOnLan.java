package app.tvmouse.phone;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/** Invia il "magic packet" Wake-on-LAN. Funziona solo se la TV lo supporta ed e' abilitato. */
final class WakeOnLan {
    private static final int PORT = 9;

    private WakeOnLan() {}

    /** macsCsv: indirizzi MAC separati da virgola. knownIp: ultimo IP noto della TV (puo' essere vuoto). */
    static void send(final String macsCsv, final String knownIp) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                List<InetAddress> targets = new ArrayList<>(Discovery.broadcastTargets());
                try {
                    if (knownIp != null && knownIp.matches("^\\d{1,3}(\\.\\d{1,3}){3}$")) {
                        targets.add(InetAddress.getByName(knownIp));
                    }
                } catch (IOException ignored) {
                    // uso solo i broadcast
                }
                try (DatagramSocket socket = new DatagramSocket()) {
                    socket.setBroadcast(true);
                    for (String mac : macsCsv.split(",")) {
                        byte[] packet = magicPacket(mac.trim());
                        if (packet == null) continue;
                        for (InetAddress target : targets) {
                            try {
                                socket.send(new DatagramPacket(packet, packet.length, target, PORT));
                            } catch (IOException ignored) {
                                // questa interfaccia non permette il broadcast
                            }
                        }
                    }
                } catch (IOException ignored) {
                    // rete non disponibile
                }
            }
        }, "tvmouse-wol");
        t.setDaemon(true);
        t.start();
    }

    /** 6 byte 0xFF seguiti da 16 volte il MAC. Ritorna null se il MAC non e' valido. */
    static byte[] magicPacket(String mac) {
        String[] parts = mac.split(":");
        if (parts.length != 6) return null;
        byte[] bytes = new byte[6];
        try {
            for (int i = 0; i < 6; i++) {
                int v = Integer.parseInt(parts[i], 16);
                if (v < 0 || v > 255) return null;
                bytes[i] = (byte) v;
            }
        } catch (NumberFormatException e) {
            return null;
        }
        byte[] packet = new byte[6 + 16 * 6];
        for (int i = 0; i < 6; i++) packet[i] = (byte) 0xFF;
        for (int i = 0; i < 16; i++) System.arraycopy(bytes, 0, packet, 6 + i * 6, 6);
        return packet;
    }
}
