package top.niunaijun.blackbox.utils;

public class SimpleCrashFix {
    private static final String TAG = "SimpleCrashFix";
    private static boolean sIsInstalled = false;

    /** Installs only the context wrapper hook. No global uncaught-exception handler is installed. */
    public static void installSimpleFix() {
        if (sIsInstalled) {
            return;
        }
        try {
            ContextWrapperHook.installHook();
            sIsInstalled = true;
        } catch (Exception e) {
            Slog.e(TAG, "Failed to install context wrapper hook: " + e.getMessage(), e);
        }
    }
}
