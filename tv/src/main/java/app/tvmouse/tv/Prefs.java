package app.tvmouse.tv;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;
import java.util.Locale;

/** PIN di accoppiamento e porta ADB. */
final class Prefs {
    private static final String FILE = "tvmouse";

    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static synchronized String pin(Context c) {
        String pin = sp(c).getString("pin", null);
        return pin != null ? pin : newPin(c);
    }

    static synchronized String newPin(Context c) {
        String pin = String.format(Locale.ROOT, "%06d", new SecureRandom().nextInt(1000000));
        sp(c).edit().putString("pin", pin).apply();
        return pin;
    }

    /** Se false la TV accetta qualsiasi telefono nella rete (la connessione resta cifrata). */
    static boolean requirePin(Context c) {
        return sp(c).getBoolean("require_pin", true);
    }

    static void setRequirePin(Context c, boolean value) {
        sp(c).edit().putBoolean("require_pin", value).apply();
    }

    static int adbPort(Context c) {
        return sp(c).getInt("adb_port", 5555);
    }

    static void setAdbPort(Context c, int port) {
        sp(c).edit().putInt("adb_port", port).apply();
    }
}
