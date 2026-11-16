package top.niunaijun.blackbox.core.device;

import android.os.Build;
import android.os.Bundle;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import top.niunaijun.blackbox.core.NativeCore;
import top.niunaijun.blackbox.entity.device.BDeviceProfile;
import top.niunaijun.blackbox.utils.Slog;

/**
 * Applies a {@link BDeviceProfile} inside the clone process: android.os.Build fields and
 * native system properties. Identifier proxies read {@link #current()}.
 */
public class DeviceProfileApplier {
    public static final String TAG = "DeviceProfileApplier";
    private static volatile BDeviceProfile sCurrent;

    public static BDeviceProfile current() {
        return sCurrent;
    }

    public static void apply(BDeviceProfile profile) {
        sCurrent = profile;
        if (profile == null) {
            return;
        }
        setBuildField("BRAND", profile.brand);
        setBuildField("MANUFACTURER", profile.manufacturer);
        setBuildField("MODEL", profile.model);
        setBuildField("DEVICE", profile.device);
        setBuildField("PRODUCT", profile.product);
        setBuildField("BOARD", profile.board);
        setBuildField("HARDWARE", profile.hardware);
        setBuildField("FINGERPRINT", profile.fingerprint);
        setBuildField("SERIAL", profile.serial);

        List<String> keys = new ArrayList<>();
        List<String> values = new ArrayList<>();
        addProp(keys, values, "ro.product.brand", profile.brand);
        addProp(keys, values, "ro.product.manufacturer", profile.manufacturer);
        addProp(keys, values, "ro.product.model", profile.model);
        addProp(keys, values, "ro.product.device", profile.device);
        addProp(keys, values, "ro.product.name", profile.product);
        addProp(keys, values, "ro.product.board", profile.board);
        addProp(keys, values, "ro.hardware", profile.hardware);
        addProp(keys, values, "ro.build.fingerprint", profile.fingerprint);
        addProp(keys, values, "ro.serialno", profile.serial);
        addProp(keys, values, "ro.boot.serialno", profile.serial);
        if (!keys.isEmpty()) {
            try {
                NativeCore.setSpoofProps(keys.toArray(new String[0]), values.toArray(new String[0]));
            } catch (Throwable e) {
                Slog.e(TAG, "setSpoofProps failed", e);
            }
        }
    }

    /**
     * If this is a Settings.Secure android_id read (ContentProvider call GET_secure) and the
     * profile sets androidId, returns a copy of the result bundle carrying the profile value.
     */
    public static Object patchSettingsCall(Object[] args, Object result) {
        BDeviceProfile p = sCurrent;
        if (p == null || p.androidId == null || args == null || !(result instanceof Bundle)) {
            return result;
        }
        for (int i = 0; i + 1 < args.length; i++) {
            if ("GET_secure".equals(args[i]) && "android_id".equals(args[i + 1])) {
                Bundle out = new Bundle((Bundle) result);
                out.putString("value", p.androidId);
                return out;
            }
        }
        return result;
    }

    private static void addProp(List<String> keys, List<String> values, String key, String value) {
        if (value != null) {
            keys.add(key);
            values.add(value);
        }
    }

    private static void setBuildField(String name, String value) {
        if (value == null) {
            return;
        }
        try {
            Field f = Build.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(null, value);
        } catch (Throwable e) {
            Slog.w(TAG, "Could not set Build." + name + ": " + e);
        }
    }
}
