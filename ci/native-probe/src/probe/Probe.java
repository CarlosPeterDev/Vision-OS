package probe;

import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Looper;
import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;

/**
 * Runs with app_process as the shell user (uid 2000), the identity Shizuku's ADB mode provides.
 * It tests what the platform allows. It does not use Shizuku itself, and it does not show that a
 * surface passed from the Nexus app process works.
 */
public final class Probe {
    private static final int PUBLIC = 1, PRESENTATION = 1 << 1, OWN_CONTENT_ONLY = 1 << 3,
        SUPPORTS_TOUCH = 1 << 6, DESTROY_ON_REMOVAL = 1 << 8, TRUSTED = 1 << 10, OWN_DISPLAY_GROUP = 1 << 11,
        ALWAYS_UNLOCKED = 1 << 12, OWN_FOCUS = 1 << 14;

    private static final class ShellContext extends ContextWrapper {
        ShellContext(Context base) { super(base); }
        @Override public String getPackageName() { return "com.android.shell"; }
        @Override public String getOpPackageName() { return "com.android.shell"; }
        @Override public AttributionSource getAttributionSource() {
            return new AttributionSource.Builder(2000).setPackageName("com.android.shell").build();
        }
    }

    private static int failures = 0;

    private static void report(String name, boolean passed, String detail) {
        System.out.println((passed ? "PASS " : "FAIL ") + name + (detail.isEmpty() ? "" : " : " + detail));
        if (!passed) failures++;
    }

    public static void main(String[] args) {
        try { run(); } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            report("probe", false, cause.toString());
        }
        System.out.println("failures=" + failures);
        System.exit(0);
    }

    private static void run() throws Exception {
        System.out.println("sdk=" + Build.VERSION.SDK_INT + " uid=" + android.os.Process.myUid());
        Looper.prepareMainLooper();
        Class<?> threadClass = Class.forName("android.app.ActivityThread");
        Object thread = threadClass.getMethod("systemMain").invoke(null);
        Context system = (Context) threadClass.getMethod("getSystemContext").invoke(thread);
        Constructor<DisplayManager> constructor = DisplayManager.class.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        DisplayManager manager = constructor.newInstance(new ShellContext(system));

        ImageReader reader = ImageReader.newInstance(720, 480, PixelFormat.RGBA_8888, 3);
        int basic = PUBLIC | PRESENTATION | OWN_CONTENT_ONLY | SUPPORTS_TOUCH | DESTROY_ON_REMOVAL;
        int trusted = basic | TRUSTED | OWN_DISPLAY_GROUP | ALWAYS_UNLOCKED;
        if (Build.VERSION.SDK_INT >= 34) trusted |= OWN_FOCUS;
        VirtualDisplay display = null;
        try {
            display = manager.createVirtualDisplay("nexus-probe", 720, 480, 160, reader.getSurface(), trusted);
            report("create trusted display", display != null, "");
        } catch (SecurityException e) {
            report("create trusted display", false, e.getMessage());
        }
        if (display == null) {
            try {
                display = manager.createVirtualDisplay("nexus-probe", 720, 480, 160, reader.getSurface(), basic);
                report("create untrusted display (fallback)", display != null, "");
            } catch (SecurityException e) {
                report("create untrusted display (fallback)", false, e.getMessage());
            }
        }
        if (display == null) return;
        int id = display.getDisplay().getDisplayId();
        System.out.println("displayId=" + id);
        try {
            String launched = shell("am start -W --display " + id + " -n com.nexus.probetarget/.TargetActivity");
            System.out.println(launched);
            report("launch on the new display", launched.contains("Status: ok"), "");
            report("real pixels from the launched app (red)", waitFor(reader, 'r', 15000), last);
            shell("input -d " + id + " tap 360 240");
            report("tap delivered to that display (turns green)", waitFor(reader, 'g', 10000), last);
            // Resize onto a new buffer. The app may restart and be red again; either colour is its own pixels.
            ImageReader larger = ImageReader.newInstance(960, 540, PixelFormat.RGBA_8888, 3);
            display.resize(960, 540, 160);
            display.setSurface(larger.getSurface());
            boolean resized = waitFor(larger, '*', 15000);
            report("pixels after resizing the display", resized, last);
            shell("input -d " + id + " keyevent 4");
            Thread.sleep(4000);
            String log = shell("logcat -d -s NexusProbe");
            System.out.println(log);
            report("target ran on the virtual display", log.contains("target created on display " + id), "");
            report("Back reached the app (it closed)", log.contains("target destroyed"), "");
        } finally {
            display.release();
            Thread.sleep(2000);
            report("display released", !shell("dumpsys display").contains("\"nexus-probe\""), "");
            shell("am force-stop com.nexus.probetarget");
        }
    }

    private static String last = "no frame";

    /** Waits for a frame whose centre is red, green, or either ('*'). */
    private static boolean waitFor(ImageReader reader, char colour, long timeout) throws InterruptedException {
        long end = System.currentTimeMillis() + timeout;
        last = "no frame";
        while (System.currentTimeMillis() < end) {
            Image image = reader.acquireLatestImage();
            if (image != null) {
                Image.Plane plane = image.getPlanes()[0];
                ByteBuffer pixels = plane.getBuffer();
                int at = image.getHeight() / 2 * plane.getRowStride() + image.getWidth() / 2 * plane.getPixelStride();
                int r = pixels.get(at) & 255, g = pixels.get(at + 1) & 255, b = pixels.get(at + 2) & 255;
                image.close();
                last = "centre rgb " + r + "," + g + "," + b;
                boolean red = r > 200 && g < 60 && b < 60, green = g > 200 && r < 60 && b < 60;
                if (colour == 'r' && red || colour == 'g' && green || colour == '*' && (red || green)) return true;
            }
            Thread.sleep(200);
        }
        return false;
    }

    private static String shell(String command) {
        try {
            Process process = new ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start();
            byte[] out = process.getInputStream().readAllBytes();
            process.waitFor();
            return new String(out).trim();
        } catch (Exception e) { return e.toString(); }
    }
}
