package top.niunaijun.blackbox.entity.device;

/**
 * Per-clone device identity. Every field is nullable; null means "leave the real value".
 */
public class BDeviceProfile {
    public String brand, manufacturer, model, device, product, board, hardware;
    public String fingerprint;
    public String androidId;
    public String imei;
    public String serial;
    public String wifiMac;
}
