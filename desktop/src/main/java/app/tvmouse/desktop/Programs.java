package app.tvmouse.desktop;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Elenco e avvio dei programmi installati sul computer. Il telefono puo' avviare soltanto
 * voci presenti nell'ultimo elenco inviato: non esegue mai comandi arbitrari.
 */
final class Programs {

    static final class Item {
        final String id;
        final String name;
        final File file;
        final String exec; // solo Linux

        Item(String id, String name, File file, String exec) {
            this.id = id;
            this.name = name;
            this.file = file;
            this.exec = exec;
        }
    }

    private volatile Map<String, Item> current = new HashMap<>();

    /** Rilegge i programmi installati e li ordina per nome. */
    List<Item> refresh() {
        Map<String, Item> byName = new LinkedHashMap<>();
        if (Injector.WINDOWS) {
            String pd = System.getenv("ProgramData");
            String ad = System.getenv("APPDATA");
            if (pd != null) scanWindows(new File(pd, "Microsoft\\Windows\\Start Menu\\Programs"), byName, 0);
            if (ad != null) scanWindows(new File(ad, "Microsoft\\Windows\\Start Menu\\Programs"), byName, 0);
        } else if (Injector.MAC) {
            String home = System.getProperty("user.home");
            for (String d : new String[]{"/Applications", "/System/Applications", "/System/Applications/Utilities",
                    "/Applications/Utilities", home + "/Applications"}) {
                scanMac(new File(d), byName);
            }
        } else {
            scanLinux(byName);
        }
        List<Item> items = new ArrayList<>(byName.values());
        Collections.sort(items, Comparator.comparing(i -> i.name.toLowerCase(Locale.ROOT)));
        Map<String, Item> map = new HashMap<>();
        List<Item> numbered = new ArrayList<>();
        for (int i = 0; i < items.size() && i < 300; i++) {
            Item it = items.get(i);
            Item n = new Item("p" + i, it.name, it.file, it.exec);
            map.put(n.id, n);
            numbered.add(n);
        }
        current = map;
        return numbered;
    }

    boolean launch(String id) {
        Item it = current.get(id);
        if (it == null) return false;
        try {
            if (Injector.WINDOWS) {
                Desktop.getDesktop().open(it.file);
            } else if (Injector.MAC) {
                new ProcessBuilder("open", it.file.getPath()).start();
            } else {
                launchLinux(it);
            }
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------ Windows

    private void scanWindows(File dir, Map<String, Item> out, int depth) {
        File[] files = dir.listFiles();
        if (files == null || depth > 3) return;
        for (File f : files) {
            if (f.isDirectory()) {
                scanWindows(f, out, depth + 1);
            } else if (f.getName().toLowerCase(Locale.ROOT).endsWith(".lnk")) {
                String name = f.getName().substring(0, f.getName().length() - 4);
                String low = name.toLowerCase(Locale.ROOT);
                if (low.contains("uninstall") || low.contains("disinstalla") || low.contains("readme")
                        || low.contains("manual") || low.contains("help")) continue;
                out.putIfAbsent(low, new Item("", name, f, null));
            }
        }
    }

    // ------------------------------------------------------------ macOS

    private void scanMac(File dir, Map<String, Item> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.getName().endsWith(".app")) {
                String name = f.getName().substring(0, f.getName().length() - 4);
                out.putIfAbsent(name.toLowerCase(Locale.ROOT), new Item("", name, f, null));
            }
        }
    }

    // ------------------------------------------------------------ Linux

    private void scanLinux(Map<String, Item> out) {
        List<File> dirs = new ArrayList<>();
        String xdg = System.getenv("XDG_DATA_DIRS");
        for (String d : (xdg == null || xdg.isEmpty() ? "/usr/local/share:/usr/share" : xdg).split(":")) {
            dirs.add(new File(d, "applications"));
        }
        dirs.add(new File("/var/lib/flatpak/exports/share/applications"));
        dirs.add(new File("/var/lib/snapd/desktop/applications"));
        String home = System.getProperty("user.home");
        dirs.add(new File(home, ".local/share/applications"));
        dirs.add(new File(home, ".local/share/flatpak/exports/share/applications"));
        for (File d : dirs) {
            File[] files = d.listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (!f.getName().endsWith(".desktop")) continue;
                Item it = parseDesktop(f);
                if (it != null) out.putIfAbsent(it.name.toLowerCase(Locale.ROOT), it);
            }
        }
    }

    private Item parseDesktop(File f) {
        List<String> lines;
        try {
            lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return null;
        }
        String name = null;
        String exec = null;
        String type = "Application";
        boolean hidden = false;
        boolean inEntry = false;
        for (String l : lines) {
            String t = l.trim();
            if (t.startsWith("[")) {
                inEntry = t.equals("[Desktop Entry]");
                continue;
            }
            if (!inEntry) continue;
            if (t.startsWith("Name=") && name == null) name = t.substring(5);
            else if (t.startsWith("Exec=") && exec == null) exec = t.substring(5);
            else if (t.startsWith("Type=")) type = t.substring(5);
            else if (t.equals("NoDisplay=true") || t.equals("Hidden=true")) hidden = true;
        }
        if (hidden || name == null || name.isEmpty() || exec == null || !type.equals("Application")) return null;
        return new Item("", name, f, exec);
    }

    private void launchLinux(Item it) throws IOException {
        List<String> cmd = tokenize(it.exec);
        if (cmd.isEmpty()) throw new IOException("comando vuoto");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.start();
    }

    /** Divide la riga Exec= in argomenti, togliendo i segnaposto %f %u ecc. */
    static List<String> tokenize(String exec) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        for (int i = 0; i < exec.length(); i++) {
            char c = exec.charAt(i);
            if (c == '"') {
                quoted = !quoted;
                any = true;
            } else if (c == '\\' && quoted && i + 1 < exec.length()) {
                cur.append(exec.charAt(++i));
            } else if (c == '%' && i + 1 < exec.length()) {
                char n = exec.charAt(++i);
                if (n == '%') cur.append('%');
                // gli altri codici (%f, %U, ...) vengono ignorati
            } else if (Character.isWhitespace(c) && !quoted) {
                if (any || cur.length() > 0) out.add(cur.toString());
                cur.setLength(0);
                any = false;
            } else {
                cur.append(c);
            }
        }
        if (any || cur.length() > 0) out.add(cur.toString());
        return out;
    }
}
