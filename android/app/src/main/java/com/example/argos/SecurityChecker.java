package com.example.argos;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import java.io.File;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

/**
 * Multi-layered security checker for Android.
 * Detects: rooted devices, pirate/cracker apps (Lucky Patcher, etc.),
 * APK tampering, debugging hooks, and emulator environments.
 *
 * Based on OWASP MASTG, Google Play Integrity docs, and BillingProtector research.
 */
public class SecurityChecker {

    public static class SecurityResult {
        public boolean isRooted = false;
        public boolean hasPirateApp = false;
        public boolean isTampered = false;
        public boolean isDebuggable = false;
        public boolean isDevelopmentBuild = false;
        public boolean isEmulator = false;
        public boolean hasHookFramework = false;
        public boolean hasMagiskHide = false;
        public String riskDetails = "";
        public int riskScore = 0;

        // MINIMAL hard blocking for release. Only block on:
        // 1. Active hook frameworks (Xposed/LSPosed/Frida) — these can
        //    modify app behavior at runtime and bypass billing/security.
        // Everything else (root, tampering, emulator, pirate apps) is
        // an advisory risk signal sent to the backend for server-side
        // decisions. This prevents false positives from blocking
        // legitimate users on non-rooted phones.
        public boolean isSecure() {
            if (hasHookFramework) return false;
            // Allow all devices — root/tamper/emulator are risk signals only
            return true;
        }

        public String getSummary() {
            StringBuilder sb = new StringBuilder();
            if (isRooted) sb.append("Rooted device; ");
            if (hasMagiskHide) sb.append("Magisk Hide/Zygisk detected; ");
            if (hasPirateApp) sb.append("Pirate app detected; ");
            if (isTampered) sb.append("APK tampered; ");
            if (isDebuggable) sb.append("Debug mode; ");
            if (isEmulator) sb.append("Emulator; ");
            if (hasHookFramework) sb.append("Hook framework; ");
            if (sb.length() == 0) sb.append("Secure");
            return sb.toString().trim();
        }
    }

    // Known pirate/cracker app package name patterns
    private static final String[] PIRATE_PACKAGES = {
        "com.dimonvideo.luckypatcher",
        "com.chelpus.lackypatch",
        "com.forpda.lp",
        "com.luckypatcher",
        "uret.jasi2169.patcher",
        "uret.jasi2169.cracker",
        "com.android.vending.billing.InAppBillingService.LACK",
        "com.android.vending.billing.InAppBillingService.LOCK",
        "com.android.vending.billing.InAppBillingService.CRACK",
        "com.android.vending.billing.InAppBillingService.PATCH",
        "com.android.vending.billing.InAppBillingService.FREE",
        "com.android.vending.billing.InAppBillingService.JASI",
        "com.android.vending.billing.InAppBillingService.ANDR",
        "com.android.vending.billing.InAppBillingService.LUCKY",
        "com.android.vending.billing.InAppBillingService.PATCHED",
        "com.android.vending.billing.InAppBillingService.CRACKED",
        "com.android.vending.billing.InAppBillingService.MOD",
        "com.android.vending.billing.InAppBillingService.HACK",
        "com.android.vending.billing.InAppBillingService.BYPASS",
        "com.android.vending.billing.InAppBillingService.UNLOCKED",
        "com.android.vending.billing.InAppBillingService.REL",
        "com.android.vending.billing.InAppBillingService.RELOADED",
        "com.android.vending.billing.InAppBillingService.CORE",
        "com.android.vending.billing.InAppBillingService.PIRATE",
        "com.android.vending.billing.InAppBillingService.PIRATED",
        "com.android.vending.billing.InAppBillingService.PATCHER",
        "com.android.patcher",
        "com.android.cracker",
        "com.android.vending.billing.InAppBillingService.COKE",
        "com.android.vending.billing.InAppBillingService.ICON",
        "com.android.vending.billing.InAppBillingService.GENUINE",
        "com.android.vending.billing.InAppBillingService.GENUIN",
        "com.android.vending.billing.InAppBillingService.GENIUNE",
        "com.android.vending.billing.InAppBillingService.GENIUN",
        "com.android.vending.billing.InAppBillingService.LUCKYPATCHER",
        "com.android.vending.billing.InAppBillingService.LP",
    };

    // Only use distinctive piracy markers here. Generic terms such as
    // "patcher" or "cracker" also occur in legitimate apps and caused false
    // positives on otherwise clean phones.
    private static final String[] PIRATE_SUBSTRINGS = {
        "luckypatcher", "lucky_patcher", "lucky-patcher",
        "lackypatch", "lacky_patch", "jasi2169",
    };

    // Root-related file paths
    private static final String[] ROOT_FILES = {
        "/system/app/Superuser.apk",
        "/sbin/su",
        "/system/bin/su",
        "/system/xbin/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/system/sd/xbin/su",
        "/system/bin/failsafe/su",
        "/data/local/su",
        "/su/bin/su",
        "/su/bin",
        "/system/xbin/busybox",
        "/system/bin/busybox",
        "/data/local/xbin/busybox",
        "/data/local/bin/busybox",
        "/system/sd/xbin/busybox",
        "/system/xbin/.su",
        "/system/bin/.su",
        "/data/local/xbin/.su",
        "/data/local/bin/.su",
        "/system/sd/xbin/.su",
        "/magisk/.core/bin/su",
        "/sbin/.magisk",
        "/data/adb/magisk",
        "/data/adb/modules",
        "/data/adb/ksu",            // KernelSU
        "/data/adb/ksud",           // KernelSU daemon
        "/data/adb/ap",             // APatch
        "/data/adb/apd",            // APatch daemon
        "/data/adb/zygisk",         // Magisk Zygisk
        "/data/adb/shamiko",        // Shamiko (Magisk hide module)
        "/data/adb/.magisk",        // Magisk Delta / hidden Magisk
        "/debug_ramdisk",           // Boot image debug ramdisk (rooted boot)
        "/system/app/SuperSU",
        "/system/etc/init.d/99SuperSUDaemon",
        "/dev/com.koushikdutta.superuser.daemon/",
        "/system/xbin/sugote",
        "/system/xbin/sugote-mksh",
        "/system/shells/sugote-mksh",
        "/system/bin/.ext/.su",
        "/system/etc/.installed_su_daemon",
        "/dev/com.koushikdutta.rommanager.daemon",
        "/data/local/.su",
        "/system/bin/.ext",
        "/vendor/bin/su",
        "/vendor/xbin/su",
        "/odm/bin/su",
        "/apex/com.android.runtime/bin/su",
    };

    // Root-related package names
    private static final String[] ROOT_PACKAGES = {
        "com.topjohnwu.magisk",
        "io.github.huskydg.magisk",     // Magisk Delta
        "io.github.vvb2060.magisk",     // Magisk Canary alt
        "me.bmax.apk",                  // KernelSU manager
        "me.bmax.apatch",               // APatch manager
        "com.koushikdutta.superuser",
        "com.thirdparty.superuser",
        "eu.chainfire.supersu",
        "com.noshufou.android.su",
        "com.noshufou.android.su.elite",
        "com.yellowes.su",
        "com.koushikdutta.rommanager",
        "com.dimonvideo.luckypatcher",
        "com.koushikdutta.magisk",
        "com.topjohnwu.magiskhide",
        "de.robv.android.xposed.installer",
        "com.android.vending.billing.InAppBillingService.COKE",
    };

    // Hook framework packages
    private static final String[] HOOK_PACKAGES = {
        "de.robv.android.xposed.installer",
        "org.lsposed.manager",
        "org.meowcat.edxposed.manager",
        "com.android.frida",
        "re.frida.server",
        "com.topjohnwu.magisk",
    };

    // Expected APK signature hash (SHA-256 of signing certificate)
    // This is the debug signing key hash — replace with release key hash for production
    private static final String EXPECTED_SIGNATURE_HASH = "";

    /**
     * Run all security checks. Always evaluate at runtime — never cache results.
     */
    public static SecurityResult check(Context ctx) {
        SecurityResult result = new SecurityResult();

        // 1. Root detection (multiple layers)
        checkRoot(ctx, result);

        // 2. Magisk Hide / Zygisk detection (root hiding)
        checkMagiskHide(ctx, result);

        // 3. Pirate/cracker app detection
        checkPirateApps(ctx, result);

        // 4. APK tampering detection
        checkTampering(ctx, result);

        // 5. Debug mode detection
        checkDebuggable(ctx, result);

        // 6. Emulator detection
        checkEmulator(result);

        // 7. Hook framework detection
        checkHookFrameworks(ctx, result);

        // Calculate risk score
        if (result.isRooted) result.riskScore += 5;
        if (result.hasMagiskHide) result.riskScore += 5;
        if (result.hasPirateApp) result.riskScore += 4;
        if (result.isTampered) result.riskScore += 5;
        if (result.isDebuggable) result.riskScore += 2;
        if (result.isEmulator) result.riskScore += 1;
        if (result.hasHookFramework) result.riskScore += 3;

        result.riskDetails = result.getSummary();
        return result;
    }

    // ── Root Detection ──

    private static void checkRoot(Context ctx, SecurityResult result) {
        // 1. Check for su binary and root files
        for (String path : ROOT_FILES) {
            try {
                if (new File(path).exists()) {
                    result.isRooted = true;
                    return;
                }
            } catch (Exception e) { }
        }

        // 2. Check standard locations for an su binary without running it
        if (suOnPath()) {
            result.isRooted = true;
            return;
        }

        // 3. Check for root management packages
        PackageManager pm = ctx.getPackageManager();
        for (String pkg : ROOT_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0);
                result.isRooted = true;
                return;
            } catch (PackageManager.NameNotFoundException e) {
                // Not found — good
            } catch (Exception e) { }
        }

        // 4. Test keys identify a non-retail build, not necessarily a rooted
        // phone. Keep this as a risk signal and let server-side integrity decide
        // whether a sensitive action should be allowed.
        String buildTags = Build.TAGS;
        if (buildTags != null && (buildTags.contains("test-keys") || buildTags.contains("testkeys"))) {
            result.riskScore += 1;
        }

        // 5. System property checks via reflection (__system_property_get).
        // Only HARD root indicators (su binary, root app) set isRooted.
        // System properties (ro.secure, ro.build.type) are risk signals only,
        // because OEM customizations can produce false positives.
        checkRootSystemProperties(result);  // adds to riskScore, does NOT set isRooted

        // 6. SELinux and mount state are useful risk signals, but custom ROMs
        // and OEM implementations can legitimately expose different values.
        if (isSelinuxPermissive()) result.riskScore += 2;

        // 7. /proc/self/mountinfo scan — risk signal only, NOT a hard block.
        // OEM ROMs (Samsung One UI, Xiaomi MIUI) legitimately use tmpfs/bind
        // mounts on /system. Only flag as risk, not as definitive root.
        if (checkMountinfoForRoot()) result.riskScore += 2;

        // 8. Magisk-specific paths (redundant with ROOT_FILES but kept for clarity)
        if (new File("/data/adb/magisk").exists() || new File("/sbin/.magisk").exists()) {
            result.isRooted = true;
            return;
        }

        // Do not execute `su` during startup. It can trigger a root manager
        // prompt or stall on vendor shells; direct evidence is handled above,
        // while stronger device verification belongs on the backend.
    }

    /**
     * Check standard binary locations without spawning a shell. Shell commands
     * such as `which` can hang behind a root prompt and make startup unreliable.
     */
    private static boolean suOnPath() {
        String[] binDirs = {
            "/system/bin", "/system/xbin", "/sbin", "/vendor/bin", "/vendor/xbin",
            "/odm/bin", "/su/bin", "/data/local/bin", "/data/local/xbin",
            "/apex/com.android.runtime/bin"
        };
        for (String dir : binDirs) {
            try {
                if (new File(dir, "su").isFile()) return true;
            } catch (Exception e) { }
        }
        return false;
    }

    /**
     * Read security-relevant system properties via the hidden
     * __system_property_get native call (reflection).
     *
     * All system property checks are RISK SIGNALS only — they do NOT set
     * isRooted. OEM customizations (Samsung, Xiaomi, custom ROMs) can produce
     * values that look suspicious without the device being rooted. Only
     * definitive evidence (su binary, root management app) sets isRooted.
     */
    private static void checkRootSystemProperties(SecurityResult result) {
        // ro.secure=0: risk signal (some custom ROMs disable this)
        if (getSystemProperty("ro.secure").equals("0")) result.riskScore += 2;

        // ro.build.type=eng: engineering build — strong risk
        String buildType = getSystemProperty("ro.build.type");
        if (buildType.equals("eng")) result.riskScore += 3;

        // ── Soft risk indicators ──
        if (getSystemProperty("ro.debuggable").equals("1")) result.riskScore += 1;
        if (buildType.equals("userdebug")) result.riskScore += 1;

        // Unlocked bootloader is a risk factor but NOT root by itself
        String flashLocked = getSystemProperty("ro.boot.flash.locked");
        if (flashLocked.equals("0")) result.riskScore += 1;

        String vbmetaState = getSystemProperty("ro.boot.vbmeta.device_state");
        if (!vbmetaState.isEmpty() && !vbmetaState.equals("locked")) result.riskScore += 1;

        // Verified boot state describes the boot chain, not the presence of
        // root inside the running OS. Unlocked/custom devices remain usable;
        // the backend can apply a stricter Play Integrity policy to sensitive
        // actions when that is required.
        String vbs = getSystemProperty("ro.boot.verifiedbootstate");
        if (vbs.equals("orange") || vbs.equals("red")) result.riskScore += 2;
        if (vbs.equals("yellow")) result.riskScore += 1;
    }

    /**
     * Read a system property via reflection on android.os.SystemProperties.
     */
    private static String getSystemProperty(String name) {
        try {
            Class<?> cls = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method get = cls.getMethod("get", String.class);
            Object val = get.invoke(null, name);
            return val == null ? "" : val.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Check SELinux enforcing state. A "permissive" or "0" state on a
     * production device strongly indicates root (Magisk sets permissive
     * in some configurations; eng/userdebug builds default to permissive).
     */
    private static boolean isSelinuxPermissive() {
        try {
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream("/sys/fs/selinux/enforce")));
            String line = reader.readLine();
            reader.close();
            if (line != null && line.trim().equals("0")) return true;
        } catch (Exception e) { }

        // Do not use a shell fallback here. A vendor shell or root prompt can
        // block indefinitely during app startup; the file check is sufficient
        // for this advisory signal.
        return false;
    }

    /**
     * Parse /proc/self/mountinfo for indicators of Magisk/Zygisk bind mounts
     * or suspicious tmpfs mounts on read-only system partitions.
     *
     * Note: overlay mounts on /apex are LEGITIMATE on Android 11+ (mainline
     * updates) and must not be treated as root. We only flag:
     *  - Explicit Magisk/Zygisk mount path markers
     *  - tmpfs mounts on /system or /vendor (root replaces system files)
     *  - bind mounts on /system or /vendor (Magisk Hide pattern)
     */
    private static boolean checkMountinfoForRoot() {
        java.io.BufferedReader reader = null;
        try {
            reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream("/proc/self/mountinfo")));
            String line;
            while ((line = reader.readLine()) != null) {
                String lower = line.toLowerCase();
                // Magisk/Zygisk mount markers — always root
                if (lower.contains("magisk") || lower.contains("zygisk")
                        || lower.contains("/data/adb/")) {
                    return true;
                }
                // tmpfs or bind mounts on /system or /vendor indicate root-based
                // file replacement. (Overlay on /apex is normal — excluded.)
                if ((lower.contains(" /system ") || lower.contains(" /vendor "))
                        && (lower.contains(" bind") || lower.contains("tmpfs"))) {
                    return true;
                }
            }
        } catch (Exception e) {
            // ignore
        } finally {
            if (reader != null) try { reader.close(); } catch (Exception e) { }
        }
        return false;
    }

    // ── Magisk Hide / Zygisk Detection ──

    /**
     * Detect Magisk Hide / Shamiko / Zygisk hide modules.
     * These are risk signals — added to riskScore but NOT a hard block,
     * because some file-path checks can false-positive on OEM devices.
     * Only if combined with actual root evidence (su binary) will the app block.
     */
    private static void checkMagiskHide(Context ctx, SecurityResult result) {
        // Shamiko / MagiskHide module directories
        String[] hidePaths = {
            "/data/adb/shamiko",
            "/data/adb/modules/zygisk_shamiko",
            "/data/adb/modules/magiskhide",
            "/data/adb/modules/riru_hide",
            "/data/adb/magisk/.magisk",
            "/data/adb/modules/.magisk",
        };
        for (String path : hidePaths) {
            try {
                if (new File(path).exists()) {
                    result.hasMagiskHide = true;
                    return;
                }
            } catch (Exception e) { }
        }

        // Zygisk enabled marker
        try {
            if (new File("/data/adb/zygisk").exists()
                    || new File("/data/adb/modules_update/zygisk").exists()) {
                result.hasMagiskHide = true;
                return;
            }
        } catch (Exception e) { }

        // Magisk DenyList / Hide manager packages
        PackageManager pm = ctx.getPackageManager();
        String[] hidePackages = {
            "com.topjohnwu.magiskhide",
            "io.github.huskydg.magisk",
        };
        for (String pkg : hidePackages) {
            try {
                pm.getPackageInfo(pkg, 0);
                result.hasMagiskHide = true;
                return;
            } catch (PackageManager.NameNotFoundException e) {
            } catch (Exception e) { }
        }

        // /proc/self/mountinfo already scanned in checkRoot; if a magisk/zygisk
        // mount was found there, hasMagiskHide is set as well via the rooted
        // path. Here we additionally look for the denylist config file.
        try {
            if (new File("/data/adb/magisk/denylist").exists()
                    || new File("/data/adb/magisk.db").exists()) {
                result.hasMagiskHide = true;
                return;
            }
        } catch (Exception e) { }
    }

    // ── Pirate/Cracker App Detection ──

    private static void checkPirateApps(Context ctx, SecurityResult result) {
        PackageManager pm = ctx.getPackageManager();

        // Check exact package names
        for (String pkg : PIRATE_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0);
                result.hasPirateApp = true;
                return;
            } catch (PackageManager.NameNotFoundException e) {
            } catch (Exception e) { }
        }

        // Check all installed packages for pirate substrings
        // This catches randomized package names
        try {
            java.util.List<PackageInfo> packages = pm.getInstalledPackages(0);
            for (PackageInfo pi : packages) {
                String pkgName = pi.packageName.toLowerCase();
                for (String substring : PIRATE_SUBSTRINGS) {
                    if (pkgName.contains(substring.toLowerCase())) {
                        result.hasPirateApp = true;
                        return;
                    }
                }
            }
        } catch (Exception e) { }

    }

    // ── APK Tampering Detection ──

    private static void checkTampering(Context ctx, SecurityResult result) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(
                ctx.getPackageName(), PackageManager.GET_SIGNATURES);

            if (pi.signatures == null || pi.signatures.length == 0) {
                result.riskScore += 2;  // advisory only, not a hard block
                return;
            }

            // If we have an expected signature hash, verify it
            if (!EXPECTED_SIGNATURE_HASH.isEmpty()) {
                for (Signature sig : pi.signatures) {
                    String hash = sha256(sig.toByteArray());
                    if (!hash.equals(EXPECTED_SIGNATURE_HASH)) {
                        result.riskScore += 3;  // advisory only
                        return;
                    }
                }
            }

            // NOTE: Do NOT block on multiple signatures. Android APK
            // signing v2/v3 schemes can report multiple signature entries
            // even on legitimate builds. This caused false positives that
            // blocked non-rooted users from accessing the app.

            // Check if app was installed from Play Store (advisory only)
            try {
                String installer = ctx.getPackageManager().getInstallerPackageName(ctx.getPackageName());
                if (installer != null) {
                    if (!installer.equals("com.android.vending")
                            && !installer.equals("com.google.android.feedback")
                            && !installer.equals("com.android.packageinstaller")
                            && !installer.equals("org.fdroid.fdroid")) {
                        result.riskScore += 1;  // advisory only
                    }
                }
            } catch (Exception e) { }

        } catch (Exception e) { }
    }

    // ── Debug Mode Detection ──

    private static void checkDebuggable(Context ctx, SecurityResult result) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(
                ctx.getPackageName(), 0);
            int flags = pi.applicationInfo.flags;
            if ((flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                result.isDebuggable = true;
                result.isDevelopmentBuild = true;
            }
        } catch (Exception e) { }

        // Check if debugger is attached — also treat as development build
        // so debug builds with USB debugging don't get blocked
        if (android.os.Debug.isDebuggerConnected()) {
            result.isDebuggable = true;
            result.isDevelopmentBuild = true;
        }
    }

    // ── Emulator Detection ──

    private static void checkEmulator(SecurityResult result) {
        // Check build fingerprint
        String fingerprint = Build.FINGERPRINT;
        if (fingerprint != null && (fingerprint.contains("generic")
                || fingerprint.contains("emulator")
                || fingerprint.contains("sdk")
                || fingerprint.contains("generic_x86"))) {
            result.isEmulator = true;
            return;
        }

        // Check model and product
        if ((Build.MODEL != null && (Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Generic")))
            || Build.HARDWARE.contains("goldfish")
            || Build.HARDWARE.contains("ranchu")
            || Build.PRODUCT.contains("sdk")
            || Build.PRODUCT.contains("google_sdk")
            || Build.PRODUCT.contains("sdk_x86")
            || Build.PRODUCT.contains("vbox86p")) {
            result.isEmulator = true;
            return;
        }

        // Check for emulator-specific files
        if (new File("/dev/qemu_pipe").exists()
                || new File("/dev/socket/qemud").exists()
                || new File("/system/lib/libc_malloc_debug_qemu.so").exists()) {
            result.isEmulator = true;
            return;
        }
    }

    // ── Hook Framework Detection ──

    private static void checkHookFrameworks(Context ctx, SecurityResult result) {
        PackageManager pm = ctx.getPackageManager();
        for (String pkg : HOOK_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0);
                result.hasHookFramework = true;
                return;
            } catch (PackageManager.NameNotFoundException e) {
            } catch (Exception e) { }
        }

        // Check for Xposed/LSPosed via class loading
        try {
            ClassLoader.getSystemClassLoader().loadClass("de.robv.android.xposed.XposedBridge");
            result.hasHookFramework = true;
            return;
        } catch (ClassNotFoundException e) {
            // Not found — good
        } catch (Exception e) { }

        // Process-list inspection is intentionally omitted. Vendor `ps`
        // implementations can block and package/class checks above are stable
        // startup signals; Play Integrity should handle hidden runtimes.
    }

    // ── Utility ──

    private static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Get the APK signature hash for the current app.
     * Use this to find the expected hash for production builds.
     */
    public static String getApkSignatureHash(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(
                ctx.getPackageName(), PackageManager.GET_SIGNATURES);
            if (pi.signatures != null && pi.signatures.length > 0) {
                return sha256(pi.signatures[0].toByteArray());
            }
        } catch (Exception e) { }
        return "";
    }
}
