package top.niunaijun.blackbox.core;


import android.os.Process;
import android.util.Log;

import androidx.annotation.Keep;

import java.io.File;
import java.util.List;

import dalvik.system.DexFile;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.utils.compat.DexFileCompat;

import top.niunaijun.blackbox.core.system.JarManager;
import top.niunaijun.blackbox.utils.Slog;


public class NativeCore {
    public static final String TAG = "NativeCore";

    static {

        System.loadLibrary("blackbox");
    }

    public static native void init(int apiLevel);

    public static native void enableIO();

    public static native void addIORule(String targetPath, String relocatePath);

    public static native void hideXposed();

    public static native boolean disableHiddenApi();
    
    public static native boolean disableResourceLoading();

    public static native void setSpoofProps(String[] keys, String[] values);


    // The engine's server process plays the part of the system for clones: it hosts their
    // activity, package and account managers. Calls it makes into a clone are reported as coming
    // from the system uid, as they would on a real device. Google Play services, for one, only
    // lets the system (or Google's own apps) drive its account authenticator.
    private static volatile int sServerPid = -1;
    private static volatile long sServerPidCheckedAt;
    private static final long SERVER_PID_TTL_MS = 30_000;

    /** Looks the server pid up off the calling thread; safe to call often. */
    public static void refreshServerPid() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (sServerPid > 0 && now - sServerPidCheckedAt < SERVER_PID_TTL_MS) {
            return;
        }
        sServerPidCheckedAt = now;
        Thread thread = new Thread(() -> {
            try {
                int pid = BlackBoxCore.getBActivityManager().getServerPid();
                if (pid > 0) {
                    sServerPid = pid;
                }
            } catch (Throwable ignored) {
            }
        }, "bb-server-pid");
        thread.setDaemon(true);
        thread.start();
    }

    private static final java.util.concurrent.ConcurrentHashMap<Integer, long[]> sPidUids =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long PID_UID_TTL_MS = 60_000;
    private static final ThreadLocal<Boolean> sResolvingPid = new ThreadLocal<>();

    /** App id of the clone process [pid], or -1. Cached briefly, since pids are reused. */
    private static int virtualUidOfPid(int pid) {
        if (pid <= 0) {
            return -1;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        long[] cached = sPidUids.get(pid);
        if (cached != null && now - cached[1] < PID_UID_TTL_MS) {
            return (int) cached[0];
        }
        if (Boolean.TRUE.equals(sResolvingPid.get())) {
            return -1; // the lookup itself must never recurse into this hook
        }
        sResolvingPid.set(Boolean.TRUE);
        try {
            int uid = BlackBoxCore.getBActivityManager().getUidByPid(pid);
            if (sPidUids.size() > 256) {
                sPidUids.clear();
            }
            sPidUids.put(pid, new long[]{uid, now});
            return uid;
        } catch (Throwable t) {
            return -1;
        } finally {
            sResolvingPid.set(Boolean.FALSE);
        }
    }

    private static boolean isCallFromEngineServer() {
        int serverPid = sServerPid;
        if (android.os.SystemClock.elapsedRealtime() - sServerPidCheckedAt >= SERVER_PID_TTL_MS) {
            refreshServerPid();
        }
        return serverPid > 0 && android.os.Binder.getCallingPid() == serverPid
                && serverPid != Process.myPid();
    }

    @Keep
    public static int getCallingUid(int origCallingUid) {
        try {
            if (origCallingUid == BlackBoxCore.getHostUid() && BlackBoxCore.get().isBlackProcess()
                    && isCallFromEngineServer()) {
                return Process.SYSTEM_UID;
            }
            // Not inside an incoming call (or the app cleared the calling identity): the caller
            // is this process itself. Report the same uid Process.myUid() gives, as on a real
            // device; apps compare the two to recognise their own calls.
            if (origCallingUid == BlackBoxCore.getHostUid() && BlackBoxCore.get().isBlackProcess()
                    && android.os.Binder.getCallingPid() == Process.myPid()) {
                return origCallingUid;
            }
            // A call from another clone process: report that process's own app id, the uid its
            // package has in a clone's package manager. (It used to be the app id of whichever
            // process happened to start this one, which is wrong for every other caller and
            // fails Android 12's check that a caller's stated identity matches the real one.)
            if (origCallingUid == BlackBoxCore.getHostUid() && BlackBoxCore.get().isBlackProcess()) {
                int virtualUid = virtualUidOfPid(android.os.Binder.getCallingPid());
                if (virtualUid > 0) {
                    return virtualUid;
                }
            }
            
            if (origCallingUid > 0 && origCallingUid < Process.FIRST_APPLICATION_UID)
                return origCallingUid;
            
            if (origCallingUid > Process.LAST_APPLICATION_UID)
                return origCallingUid;

            if (origCallingUid == BlackBoxCore.getHostUid()) {
                
                String appPackageName = BlackBoxCore.getAppPackageName();
                if (appPackageName != null && appPackageName.equals("com.google.android.gms")){
                    
                }
                
                
                
                if (appPackageName != null && appPackageName.equals("com.google.android.webview")){
                    return Process.myUid();
                }
                
                
                try {
                    int callingBUid = BlackBoxCore.getCallingBUid();
                    if (callingBUid > 0 && callingBUid < Process.LAST_APPLICATION_UID) {
                        return callingBUid;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Error getting calling BUid: " + e.getMessage());
                }
                
                
                try {
                    StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
                    for (StackTraceElement element : stackTrace) {
                        String className = element.getClassName();
                        String methodName = element.getMethodName();
                        
                        
                        if ((className.contains("Settings") || className.contains("FeatureFlag")) &&
                            (methodName.contains("getString") || methodName.contains("getInt") || 
                             methodName.contains("getLong") || methodName.contains("getFloat"))) {
                            
                            Log.d(TAG, "System settings access detected, using system UID to prevent mismatch");
                            
                            return Process.SYSTEM_UID; 
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Error analyzing stack trace for UID resolution: " + e.getMessage());
                }
                
                
                return BlackBoxCore.getHostUid();
            }
            return origCallingUid;
        } catch (Exception e) {
            Log.e(TAG, "Error in getCallingUid: " + e.getMessage());
            
            return Process.myUid();
        }
    }

    @Keep
    public static String redirectPath(String path) {
        return IOCore.get().redirectPath(path);
    }

    @Keep
    public static File redirectPath(File path) {
        return IOCore.get().redirectPath(path);
    }

    @Keep
    public static long[] loadEmptyDex() {
        try {
            File emptyJar = JarManager.getInstance().getEmptyJar();
            if (emptyJar == null) {
                Log.w(TAG, "Empty JAR not available, attempting sync initialization");
                JarManager.getInstance().initializeSync();
                emptyJar = JarManager.getInstance().getEmptyJar();
            }
            
            if (emptyJar == null || !emptyJar.exists()) {
                Log.e(TAG, "Empty JAR file not found or invalid");
                return new long[]{};
            }
            
            DexFile dexFile = new DexFile(emptyJar);
            List<Long> cookies = DexFileCompat.getCookies(dexFile);
            long[] longs = new long[cookies.size()];
            for (int i = 0; i < cookies.size(); i++) {
                longs[i] = cookies.get(i);
            }
            Log.d(TAG, "Successfully loaded empty DEX with " + cookies.size() + " cookies");
            return longs;
        } catch (Exception e) {
            Log.e(TAG, "Failed to load empty DEX", e);
        }
        return new long[]{};
    }
    
    
    private static long[] createFallbackEmptyDex() {
        try {
            Slog.d(TAG, "Creating fallback empty DEX");
            
            
            
            byte[] emptyDexBytes = createMinimalDexBytes();
            
            
            File tempDexFile = File.createTempFile("fallback_empty", ".dex");
            tempDexFile.deleteOnExit();
            
            java.io.FileOutputStream fos = new java.io.FileOutputStream(tempDexFile);
            fos.write(emptyDexBytes);
            fos.close();
            
            
            DexFile dexFile = new DexFile(tempDexFile);
            List<Long> cookies = DexFileCompat.getCookies(dexFile);
            
            if (cookies != null && !cookies.isEmpty()) {
                long[] longs = new long[cookies.size()];
                for (int i = 0; i < cookies.size(); i++) {
                    longs[i] = cookies.get(i);
                }
                
                Slog.d(TAG, "Successfully created fallback empty DEX with " + cookies.size() + " cookies");
                return longs;
            }
            
        } catch (Exception e) {
            Slog.e(TAG, "Error creating fallback empty DEX: " + e.getMessage());
        }
        
        
        Slog.w(TAG, "Returning empty DEX array as last resort");
        return new long[]{};
    }
    
    
    private static byte[] createMinimalDexBytes() {
        
        
        
        
        
        byte[] dexHeader = {
            'd', 'e', 'x', '\n',  
            0x30, 0x33, 0x35, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x70, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00,  
            0x00, 0x00, 0x00, 0x00   
        };
        
        return dexHeader;
    }
}
