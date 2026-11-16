package top.niunaijun.blackbox.fake.frameworks;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.entity.device.BDeviceProfile;
import top.niunaijun.blackbox.utils.FileUtils;
import top.niunaijun.blackbox.utils.Slog;

/**
 * Stores one JSON file per (userId, packageName) in the shared data dir, so it works from any
 * process. A profile is applied the next time the clone process starts.
 */
public class BDeviceManager {
    public static final String TAG = "BDeviceManager";
    private static final BDeviceManager sInstance = new BDeviceManager();

    public static BDeviceManager get() {
        return sInstance;
    }

    public synchronized void setProfile(int userId, String packageName, BDeviceProfile profile) {
        if (profile == null) {
            clearProfile(userId, packageName);
            return;
        }
        File file = BEnvironment.getDeviceProfileFile(userId, packageName);
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        try {
            FileUtils.mkdirs(file.getParentFile());
            JSONObject json = new JSONObject();
            put(json, "brand", profile.brand);
            put(json, "manufacturer", profile.manufacturer);
            put(json, "model", profile.model);
            put(json, "device", profile.device);
            put(json, "product", profile.product);
            put(json, "board", profile.board);
            put(json, "hardware", profile.hardware);
            put(json, "fingerprint", profile.fingerprint);
            put(json, "androidId", profile.androidId);
            put(json, "imei", profile.imei);
            put(json, "serial", profile.serial);
            put(json, "wifiMac", profile.wifiMac);
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(json.toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (!tmp.renameTo(file)) {
                throw new IOException("rename failed: " + tmp);
            }
        } catch (Throwable e) {
            Slog.e(TAG, "setProfile failed", e);
            tmp.delete();
        }
    }

    public synchronized BDeviceProfile getProfile(int userId, String packageName) {
        File file = BEnvironment.getDeviceProfileFile(userId, packageName);
        if (!file.isFile()) {
            return null;
        }
        try {
            byte[] data = new byte[(int) file.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                int off = 0;
                while (off < data.length) {
                    int n = in.read(data, off, data.length - off);
                    if (n < 0) break;
                    off += n;
                }
            }
            JSONObject json = new JSONObject(new String(data, StandardCharsets.UTF_8));
            BDeviceProfile p = new BDeviceProfile();
            p.brand = get(json, "brand");
            p.manufacturer = get(json, "manufacturer");
            p.model = get(json, "model");
            p.device = get(json, "device");
            p.product = get(json, "product");
            p.board = get(json, "board");
            p.hardware = get(json, "hardware");
            p.fingerprint = get(json, "fingerprint");
            p.androidId = get(json, "androidId");
            p.imei = get(json, "imei");
            p.serial = get(json, "serial");
            p.wifiMac = get(json, "wifiMac");
            return p;
        } catch (Throwable e) {
            Slog.e(TAG, "getProfile failed", e);
            return null;
        }
    }

    public synchronized void clearProfile(int userId, String packageName) {
        BEnvironment.getDeviceProfileFile(userId, packageName).delete();
    }

    private static void put(JSONObject json, String key, String value) throws JSONException {
        if (value != null) {
            json.put(key, value);
        }
    }

    private static String get(JSONObject json, String key) {
        return json.isNull(key) ? null : json.optString(key, null);
    }
}
