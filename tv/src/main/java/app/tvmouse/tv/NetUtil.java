package app.tvmouse.tv;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class NetUtil {
    private NetUtil() {}

    /** Indirizzi IPv4 privati della TV (Wi-Fi, Ethernet o hotspot del telefono). */
    static List<String> localIps() {
        List<String> result = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        result.add(a.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {
            // nessuna rete disponibile
        }
        return result;
    }
}
