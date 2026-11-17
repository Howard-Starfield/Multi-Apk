package top.niunaijun.blackbox.core;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.entity.pm.InstallResult;
import top.niunaijun.blackbox.utils.Slog;


public class GmsCore {
    private static final String TAG = "GmsCore";

    private static final HashSet<String> GOOGLE_APP = new HashSet<>();
    private static final HashSet<String> GOOGLE_SERVICE = new HashSet<>();
    public static final String GMS_PKG = "com.google.android.gms";
    public static final String GSF_PKG = "com.google.android.gsf";
    public static final String VENDING_PKG = "com.android.vending";

    static {
        GOOGLE_APP.add(VENDING_PKG);
        GOOGLE_APP.add("com.google.android.play.games");
        GOOGLE_APP.add("com.google.android.wearable.app");
        GOOGLE_APP.add("com.google.android.wearable.app.cn");

        
        GOOGLE_SERVICE.add(GMS_PKG);
        GOOGLE_SERVICE.add(GSF_PKG);
        GOOGLE_SERVICE.add("com.google.android.gsf.login");
        GOOGLE_SERVICE.add("com.google.android.backuptransport");
        GOOGLE_SERVICE.add("com.google.android.backup");
        GOOGLE_SERVICE.add("com.google.android.configupdater");
        GOOGLE_SERVICE.add("com.google.android.syncadapters.contacts");
        GOOGLE_SERVICE.add("com.google.android.feedback");
        GOOGLE_SERVICE.add("com.google.android.onetimeinitializer");
        GOOGLE_SERVICE.add("com.google.android.partnersetup");
        GOOGLE_SERVICE.add("com.google.android.setupwizard");
        GOOGLE_SERVICE.add("com.google.android.syncadapters.calendar");
    }

    public static boolean isGoogleService(String packageName) {
        return GOOGLE_SERVICE.contains(packageName);
    }

    public static boolean isGoogleAppOrService(String str) {
        return GOOGLE_APP.contains(str) || GOOGLE_SERVICE.contains(str);
    }

    /**
     * Installs every Google package the phone has that is not yet in {@code userId}. The packages
     * installed by this call are appended to {@code installed} so the caller can roll back exactly
     * those (and not packages the user already had).
     */
    private static InstallResult installPackages(Set<String> list, int userId, List<String> installed) {
        BlackBoxCore blackBoxCore = BlackBoxCore.get();
        for (String packageName : list) {
            if (blackBoxCore.isInstalled(packageName, userId)) {
                continue;
            }
            try {
                BlackBoxCore.getContext().getPackageManager().getApplicationInfo(packageName, 0);
            } catch (PackageManager.NameNotFoundException e) {
                
                continue;
            }
            InstallResult installResult = blackBoxCore.installPackageAsUser(packageName, userId);
            if (installResult == null || !installResult.success) {
                return installResult != null ? installResult
                        : new InstallResult().installError(packageName, "install returned no result");
            }
            installed.add(packageName);
        }
        return new InstallResult();
    }

    private static void uninstallPackages(Collection<String> list, int userId) {
        BlackBoxCore blackBoxCore = BlackBoxCore.get();
        for (String packageName : list) {
            // Per user: the engine only removes the shared copy when no other user still has it.
            blackBoxCore.uninstallPackageAsUser(packageName, userId);
        }
    }

    /** Kills every process of every package installed in {@code userId}, so apps re-bind to whatever Google services exist afterwards. */
    private static void stopAllInUser(int userId) {
        BlackBoxCore blackBoxCore = BlackBoxCore.get();
        try {
            List<PackageInfo> packages = blackBoxCore.getInstalledPackages(0, userId);
            if (packages == null) return;
            for (PackageInfo info : packages) {
                blackBoxCore.stopPackage(info.packageName, userId);
            }
        } catch (Throwable t) {
            Slog.w(TAG, "stopAllInUser(" + userId + ") failed: " + t);
        }
    }

    public static InstallResult installGApps(int userId) {
        if (!isSupportGms()) {
            return new InstallResult().installError(GMS_PKG, "This phone has no Google Play services");
        }
        Set<String> googleApps = new LinkedHashSet<>();
        // Services first: the apps depend on them.
        googleApps.addAll(GOOGLE_SERVICE);
        googleApps.addAll(GOOGLE_APP);

        stopAllInUser(userId);
        List<String> installed = new ArrayList<>();
        InstallResult installResult = installPackages(googleApps, userId, installed);
        if (installResult.success && !isInstalledGoogleService(userId)) {
            installResult = new InstallResult().installError(GMS_PKG, "Google Play services were not installed");
        }
        if (!installResult.success) {
            Slog.w(TAG, "installGApps(" + userId + ") failed, rolling back " + installed + ": " + installResult.msg);
            // Roll back only what this call added; never packages the user already had.
            uninstallPackages(installed, userId);
        }
        stopAllInUser(userId);
        return installResult;
    }

    /**
     * Removes the Google packages (and their data and the user's Google accounts, see
     * {@code BAccountManagerService.onPackageUninstalled}) from {@code userId} only.
     */
    public static void uninstallGApps(int userId) {
        stopAllInUser(userId);
        uninstallPackages(GOOGLE_SERVICE, userId);
        uninstallPackages(GOOGLE_APP, userId);
        stopAllInUser(userId);
    }

    public static void remove(String packageName) {
        GOOGLE_SERVICE.remove(packageName);
        GOOGLE_APP.remove(packageName);
    }


    public static boolean isSupportGms() {
        try {
            BlackBoxCore.getPackageManager().getPackageInfo(GMS_PKG, 0);
            return true;
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return false;
    }

    public static boolean isInstalledGoogleService(int userId) {
        return BlackBoxCore.get().isInstalled(GMS_PKG, userId);
    }
}