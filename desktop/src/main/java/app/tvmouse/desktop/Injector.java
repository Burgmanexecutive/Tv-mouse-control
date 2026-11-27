package app.tvmouse.desktop;

import java.awt.AWTException;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.util.Locale;

/** Esegue sul computer i comandi ricevuti dal telefono: mouse, tastiera, volume, spegnimento. */
final class Injector {

    static final boolean WINDOWS = os().contains("win");
    static final boolean MAC = os().contains("mac");

    private final Robot robot;
    private final Object lock = new Object();

    Injector() throws AWTException {
        robot = new Robot();
        robot.setAutoDelay(0);
    }

    private static String os() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------ mouse

    void move(int dx, int dy) {
        synchronized (lock) {
            Point p = MouseInfo.getPointerInfo().getLocation();
            Rectangle all = new Rectangle();
            for (GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                all = all.union(d.getDefaultConfiguration().getBounds());
            }
            int x = Math.max(all.x, Math.min(all.x + all.width - 1, p.x + dx));
            int y = Math.max(all.y, Math.min(all.y + all.height - 1, p.y + dy));
            robot.mouseMove(x, y);
        }
    }

    void click(int button) {
        synchronized (lock) {
            robot.mousePress(button);
            robot.delay(30);
            robot.mouseRelease(button);
        }
    }

    void leftClick() {
        click(InputEvent.BUTTON1_DOWN_MASK);
    }

    void rightClick() {
        click(InputEvent.BUTTON3_DOWN_MASK);
    }

    /** dir = +1: dito verso il basso (il contenuto sale), come sul telefono. */
    void scroll(int dir) {
        synchronized (lock) {
            robot.mouseWheel(-dir * 3);
        }
    }

    // ------------------------------------------------------------ tastiera

    private int modifier() {
        return MAC ? KeyEvent.VK_META : KeyEvent.VK_CONTROL;
    }

    void key(int vk) {
        synchronized (lock) {
            robot.keyPress(vk);
            robot.delay(15);
            robot.keyRelease(vk);
        }
    }

    void combo(int modifierKey, int vk) {
        synchronized (lock) {
            robot.keyPress(modifierKey);
            robot.keyPress(vk);
            robot.delay(15);
            robot.keyRelease(vk);
            robot.keyRelease(modifierKey);
        }
    }

    void back() {
        if (MAC) combo(KeyEvent.VK_META, KeyEvent.VK_OPEN_BRACKET);
        else combo(KeyEvent.VK_ALT, KeyEvent.VK_LEFT);
    }

    void home() {
        if (MAC) combo(KeyEvent.VK_CONTROL, KeyEvent.VK_UP); // Mission Control
        else key(WINDOWS ? KeyEvent.VK_WINDOWS : KeyEvent.VK_META);
    }

    void backspace(int n) {
        for (int i = 0; i < n; i++) key(KeyEvent.VK_BACK_SPACE);
    }

    /** Scrive un testo qualsiasi (anche accenti ed emoji) incollandolo dagli appunti. */
    void typeText(String text) {
        java.awt.datatransfer.Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
        String previous = null;
        try {
            if (cb.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                previous = (String) cb.getData(DataFlavor.stringFlavor);
            }
        } catch (Exception ignored) {
            // appunti non leggibili: non li ripristino
        }
        cb.setContents(new StringSelection(text), null);
        robot.delay(40);
        combo(modifier(), KeyEvent.VK_V);
        robot.delay(120);
        if (previous != null) cb.setContents(new StringSelection(previous), null);
    }

    // ------------------------------------------------------------ volume e riproduzione

    void volume(int step) {
        if (WINDOWS) {
            run("powershell", "-NoProfile", "-Command",
                    "(New-Object -ComObject WScript.Shell).SendKeys([char]" + (step > 0 ? 175 : 174) + ")");
        } else if (MAC) {
            run("osascript", "-e", "set volume output volume ((output volume of (get volume settings)) "
                    + (step > 0 ? "+" : "-") + " 6)");
        } else if (!run("pactl", "set-sink-volume", "@DEFAULT_SINK@", step > 0 ? "+5%" : "-5%")) {
            run("amixer", "-q", "set", "Master", step > 0 ? "5%+" : "5%-");
        }
    }

    void mute() {
        if (WINDOWS) {
            run("powershell", "-NoProfile", "-Command", "(New-Object -ComObject WScript.Shell).SendKeys([char]173)");
        } else if (MAC) {
            run("osascript", "-e", "set volume output muted not (output muted of (get volume settings))");
        } else if (!run("pactl", "set-sink-mute", "@DEFAULT_SINK@", "toggle")) {
            run("amixer", "-q", "set", "Master", "toggle");
        }
    }

    /** action: playpause, next, previous. */
    void media(String action) {
        if (WINDOWS) {
            int c = action.equals("next") ? 176 : action.equals("previous") ? 177 : 179;
            run("powershell", "-NoProfile", "-Command", "(New-Object -ComObject WScript.Shell).SendKeys([char]" + c + ")");
        } else if (!MAC) {
            run("playerctl", action.equals("playpause") ? "play-pause" : action);
        }
    }

    // ------------------------------------------------------------ spegnimento

    void shutdownNow() {
        if (WINDOWS) run("shutdown", "/s", "/t", "0");
        else if (MAC) run("osascript", "-e", "tell application \"System Events\" to shut down");
        else if (!run("systemctl", "poweroff")) run("shutdown", "-h", "now");
    }

    private static boolean run(String... cmd) {
        try {
            new ProcessBuilder(cmd).redirectErrorStream(true).start();
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
