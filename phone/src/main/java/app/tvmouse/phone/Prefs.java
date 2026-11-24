package app.tvmouse.phone;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** TV salvate (indirizzo, PIN, nome, indirizzi MAC) e opzioni dell'app. */
final class Prefs {
    private static final String FILE = "tvmouse";

    private Prefs() {}

    static final class Tv {
        final String ip;
        final String pin;
        final String name;
        /** Indirizzi MAC separati da virgola, noti solo dopo un collegamento con ADB attivo sulla TV. */
        final String macs;

        Tv(String ip, String pin, String name, String macs) {
            this.ip = ip;
            this.pin = pin;
            this.name = name;
            this.macs = macs;
        }

        String label() {
            return name + " (" + ip + ")";
        }
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------ TV salvate

    static synchronized List<Tv> tvs(Context c) {
        List<Tv> out = new ArrayList<>();
        SharedPreferences p = sp(c);
        String json = p.getString("tvs", null);
        if (json == null) {
            // versione precedente: una sola TV salvata
            String host = p.getString("host", "");
            if (!host.isEmpty()) {
                out.add(new Tv(host, p.getString("pin", ""), "TV", ""));
                store(c, out);
                p.edit().putString("last_ip", host).apply();
            }
            return out;
        }
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                String ip = o.optString("ip", "");
                if (ip.isEmpty()) continue;
                out.add(new Tv(ip, o.optString("pin", ""), o.optString("name", "TV"), o.optString("macs", "")));
            }
        } catch (JSONException ignored) {
            // elenco rovinato: riparto da vuoto
        }
        return out;
    }

    private static void store(Context c, List<Tv> list) {
        JSONArray a = new JSONArray();
        for (Tv t : list) {
            JSONObject o = new JSONObject();
            try {
                o.put("ip", t.ip);
                o.put("pin", t.pin);
                o.put("name", t.name);
                o.put("macs", t.macs);
            } catch (JSONException ignored) {
                continue;
            }
            a.put(o);
        }
        sp(c).edit().putString("tvs", a.toString()).apply();
    }

    static synchronized Tv find(Context c, String ip) {
        for (Tv t : tvs(c)) {
            if (t.ip.equals(ip)) return t;
        }
        return null;
    }

    /** Aggiunge la TV o ne aggiorna PIN e nome, conservando gli indirizzi MAC già noti. */
    static synchronized void upsert(Context c, String ip, String pin, String name) {
        List<Tv> list = tvs(c);
        boolean found = false;
        for (int i = 0; i < list.size(); i++) {
            Tv t = list.get(i);
            if (t.ip.equals(ip)) {
                list.set(i, new Tv(ip, pin, name, t.macs));
                found = true;
                break;
            }
        }
        if (!found) list.add(new Tv(ip, pin, name, ""));
        store(c, list);
    }

    static synchronized void setMacs(Context c, String ip, String macs) {
        List<Tv> list = tvs(c);
        for (int i = 0; i < list.size(); i++) {
            Tv t = list.get(i);
            if (t.ip.equals(ip)) {
                list.set(i, new Tv(t.ip, t.pin, t.name, macs));
                store(c, list);
                return;
            }
        }
    }

    static synchronized void remove(Context c, String ip) {
        List<Tv> list = tvs(c);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).ip.equals(ip)) {
                list.remove(i);
                break;
            }
        }
        store(c, list);
        if (lastIp(c).equals(ip)) setLastIp(c, "");
    }

    static String lastIp(Context c) {
        return sp(c).getString("last_ip", "");
    }

    static void setLastIp(Context c, String ip) {
        sp(c).edit().putString("last_ip", ip).apply();
    }

    static Tv last(Context c) {
        String ip = lastIp(c);
        return ip.isEmpty() ? null : find(c, ip);
    }

    // ------------------------------------------------------------ opzioni

    /** Moltiplicatore della velocità del puntatore (0,5 - 2,0). */
    static float sensitivity(Context c) {
        return sp(c).getFloat("sensitivity", 1f);
    }

    static boolean haptics(Context c) {
        return sp(c).getBoolean("haptics", true);
    }

    static boolean volumeKeys(Context c) {
        return sp(c).getBoolean("volume_keys", true);
    }

    static void saveOptions(Context c, float sensitivity, boolean haptics, boolean volumeKeys) {
        sp(c).edit()
                .putFloat("sensitivity", sensitivity)
                .putBoolean("haptics", haptics)
                .putBoolean("volume_keys", volumeKeys)
                .apply();
    }
}
