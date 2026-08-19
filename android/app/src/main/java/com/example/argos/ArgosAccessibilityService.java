package com.example.argos;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.util.Log;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

public class ArgosAccessibilityService extends AccessibilityService {

    private static final String TAG = "Argos";
    private static ArgosAccessibilityService instance;
    private static String currentApp = "";
    private static String currentAppLabel = "";
    // Track recently used apps (most recent first, max 10)
    private static final LinkedList<String> appHistory = new LinkedList<>();
    private static final int MAX_HISTORY = 10;

    // Privacy mode: when enabled, AI cannot read screen text or see app content
    private static boolean privacyMode = false;
    // Per-app blocklist: apps where AI cannot read screen or get awareness
    private static final java.util.Set<String> blockedApps = new java.util.HashSet<>();
    // Full privacy: eyes closed, no app awareness at all
    private static boolean fullPrivacy = false;

    // Packages to ignore (keyboards, IMEs, system UI, launchers that aren't real apps)
    private static boolean isIgnoredPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return true;
        if (pkg.equals("com.example.argos")) return true;
        // System UI
        if (pkg.startsWith("com.android.systemui")) return true;
        if (pkg.startsWith("android")) return true;
        // Keyboards / IMEs
        if (pkg.contains("inputmethod")) return true;
        if (pkg.contains("ime")) return true;
        if (pkg.equals("com.google.android.inputmethod.latin")) return true;
        if (pkg.equals("com.android.inputmethod.latin")) return true;
        if (pkg.startsWith("com.samsung.android.inputmethod")) return true;
        if (pkg.startsWith("com.swiftkey")) return true;
        // Common launcher packages
        if (pkg.equals("com.android.launcher")) return true;
        if (pkg.equals("com.android.launcher3")) return true;
        if (pkg.contains("launcher")) return true;
        // Notification shade / recents
        if (pkg.contains("notification")) return true;
        if (pkg.contains("recents")) return true;
        return false;
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.i(TAG, "ArgosAccessibilityService connected");

        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        info.notificationTimeout = 100;
        setServiceInfo(info);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        int type = event.getEventType();
        // Detect app switches via TYPE_WINDOW_STATE_CHANGED
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            String pkg = event.getPackageName() != null ? event.getPackageName().toString() : "";
            if (isIgnoredPackage(pkg)) return;

            // Privacy: if full privacy mode, don't track app changes at all
            if (fullPrivacy) return;

            // Privacy: if this specific app is blocked, don't notify
            // but DO tell the robot to hide its eyes
            if (blockedApps.contains(pkg)) {
                currentApp = pkg;
                currentAppLabel = getAppLabel(pkg);
                // Notify robot to hide eyes (privacy gesture)
                FloatingRobotService svc = FloatingRobotService.getInstance();
                if (svc != null) {
                    svc.onBlockedAppOpened(currentAppLabel);
                }
                return;
            }

            // If we were previously on a blocked app and now switched to a non-blocked one,
            // tell the robot to show its eyes again
            if (blockedApps.contains(currentApp) && !pkg.equals(currentApp)) {
                FloatingRobotService svc = FloatingRobotService.getInstance();
                if (svc != null) {
                    svc.onBlockedAppClosed();
                }
            }

            if (!pkg.equals(currentApp)) {
                currentApp = pkg;
                currentAppLabel = getAppLabel(pkg);
                Log.i(TAG, "App switched to: " + pkg + " (" + currentAppLabel + ")");

                // Add to history (avoid consecutive duplicates)
                synchronized (appHistory) {
                    if (appHistory.isEmpty() || !appHistory.getFirst().equals(currentAppLabel)) {
                        appHistory.addFirst(currentAppLabel);
                        if (appHistory.size() > MAX_HISTORY) appHistory.removeLast();
                    }
                }

                // Notify FloatingRobotService
                FloatingRobotService svc = FloatingRobotService.getInstance();
                if (svc != null) {
                    svc.onAppChanged(currentAppLabel);
                }
            }
        }
    }

    // Get the root node of the actual app window (not keyboard/IME)
    private AccessibilityNodeInfo getRealAppRoot() {
        // Try getWindows() first — allows us to filter out IME
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null && !windows.isEmpty()) {
                // Find an application window (not IME, not system)
                for (AccessibilityWindowInfo win : windows) {
                    if (win.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) {
                        AccessibilityNodeInfo root = win.getRoot();
                        if (root != null) {
                            String pkg = root.getPackageName() != null ? root.getPackageName().toString() : "";
                            if (!isIgnoredPackage(pkg)) {
                                return root;
                            }
                        }
                    }
                }
                // Fallback: try any non-IME window
                for (AccessibilityWindowInfo win : windows) {
                    if (win.getType() != AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                        AccessibilityNodeInfo root = win.getRoot();
                        if (root != null) {
                            String pkg = root.getPackageName() != null ? root.getPackageName().toString() : "";
                            if (!isIgnoredPackage(pkg)) {
                                return root;
                            }
                        }
                    }
                }
            }
        }
        // Final fallback: getRootInActiveWindow (may return IME)
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            String pkg = root.getPackageName() != null ? root.getPackageName().toString() : "";
            if (!isIgnoredPackage(pkg)) {
                return root;
            }
        }
        return null;
    }

    // Get friendly app name from package — never returns raw package name
    private String getAppLabel(String pkg) {
        // First try PackageManager
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            String label = (String) pm.getApplicationLabel(ai);
            if (label != null && !label.isEmpty() && !label.equals(pkg)) {
                return label;
            }
        } catch (Exception e) {
            // Fall through to extraction
        }

        // Fallback: extract a clean name from the package name
        // e.g. "com.whatsapp" -> "WhatsApp", "com.instagram.android" -> "Instagram"
        return cleanPackageName(pkg);
    }

    // Extract a human-readable name from a package name
    private static String cleanPackageName(String pkg) {
        if (pkg == null || pkg.isEmpty()) return "Unknown";

        // Split by dot and take the most meaningful part
        String[] parts = pkg.split("\\.");
        // Skip common prefixes: com, org, net, io, app, android, google, samsung, miui, etc.
        java.util.Set<String> skip = new java.util.HashSet<>(java.util.Arrays.asList(
            "com", "org", "net", "io", "app", "android", "google", "samsung",
            "miui", "huawei", "xiaomi", "oppo", "vivo", "realme", "lge", "motorola",
            "amazon", "facebook", "microsoft", "adobe", "intellij", "jetbrains",
            "whatsapp", "llc", "inc", "co", "uk", "cn", "de", "fr", "jp", "kr",
            "the", "my", "mobile", "app", "application", "client", "lite", "web"
        ));

        String bestPart = "";
        for (int i = parts.length - 1; i >= 0; i--) {
            String part = parts[i].toLowerCase();
            if (!skip.contains(part) && part.length() >= 2) {
                bestPart = parts[i];
                break;
            }
        }

        if (bestPart.isEmpty() && parts.length > 0) {
            bestPart = parts[parts.length - 1];
        }

        // Capitalize first letter
        if (bestPart.length() > 0) {
            bestPart = bestPart.substring(0, 1).toUpperCase() + bestPart.substring(1);
        }

        // Fix common known apps
        String lower = pkg.toLowerCase();
        if (lower.contains("whatsapp")) return "WhatsApp";
        if (lower.contains("instagram")) return "Instagram";
        if (lower.contains("facebook") || lower.contains("fbandroid")) return "Facebook";
        if (lower.contains("messenger") || lower.contains("orca")) return "Messenger";
        if (lower.contains("twitter") || lower.contains("com.twitter.android")) return "X";
        if (lower.contains("tiktok") || lower.contains("musical")) return "TikTok";
        if (lower.contains("snapchat")) return "Snapchat";
        if (lower.contains("telegram")) return "Telegram";
        if (lower.contains("chrome")) return "Chrome";
        if (lower.contains("firefox")) return "Firefox";
        if (lower.contains("youtube")) return "YouTube";
        if (lower.contains("gmail") || lower.contains("google.android.gm")) return "Gmail";
        if (lower.contains("spotify")) return "Spotify";
        if (lower.contains("netflix")) return "Netflix";
        if (lower.contains("discord")) return "Discord";
        if (lower.contains("reddit")) return "Reddit";
        if (lower.contains("amazon.mShop")) return "Amazon";
        if (lower.contains("paypal")) return "PayPal";
        if (lower.contains("linkedin")) return "LinkedIn";
        if (lower.contains("zoom")) return "Zoom";
        if (lower.contains("teams")) return "Teams";
        if (lower.contains("slack")) return "Slack";
        if (lower.contains("chrome")) return "Chrome";
        if (lower.contains("browser") || lower.contains("org.mozilla.firefox")) return "Browser";
        if (lower.contains("settings")) return "Settings";
        if (lower.contains("calculator")) return "Calculator";
        if (lower.contains("calendar")) return "Calendar";
        if (lower.contains("camera")) return "Camera";
        if (lower.contains("gallery") || lower.contains("photos")) return "Gallery";
        if (lower.contains("music")) return "Music";
        if (lower.contains("clock")) return "Clock";
        if (lower.contains("weather")) return "Weather";
        if (lower.contains("maps")) return "Maps";
        if (lower.contains("play.store") || lower.contains("com.android.vending")) return "Play Store";
        if (lower.contains("phone") || lower.contains("dialer")) return "Phone";
        if (lower.contains("messages") || lower.contains("messaging")) return "Messages";
        if (lower.contains("contacts")) return "Contacts";
        if (lower.contains("email") || lower.contains("mail")) return "Email";
        if (lower.contains("files") || lower.contains("filemanager")) return "Files";
        if (lower.contains("notes")) return "Notes";

        return bestPart.isEmpty() ? "Unknown App" : bestPart;
    }

    public static String getCurrentApp() {
        return currentApp;
    }

    public static String getCurrentAppLabel() {
        return currentAppLabel;
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
    }

    public static ArgosAccessibilityService getInstance() {
        return instance;
    }

    // ── Privacy Control Methods ──

    public static void setFullPrivacy(boolean enabled) {
        fullPrivacy = enabled;
    }

    public static boolean isFullPrivacy() {
        return fullPrivacy;
    }

    public static void blockApp(String packageName) {
        blockedApps.add(packageName);
    }

    public static void unblockApp(String packageName) {
        blockedApps.remove(packageName);
    }

    public static boolean isAppBlocked(String packageName) {
        return blockedApps.contains(packageName);
    }

    public static java.util.Set<String> getBlockedApps() {
        return new java.util.HashSet<>(blockedApps);
    }

    // ── Browser / Screen interaction methods (called from C++ via FloatingRobotService) ──

    // Open a URL in the default browser
    public String openUrl(String url) {
        try {
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://" + url;
            }
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            return "{\"status\":\"Opening URL: " + url + "\"}";
        } catch (Exception e) {
            return "{\"error\":\"Failed to open URL: " + e.getMessage() + "\"}";
        }
    }

    // Get all text content currently visible on screen
    public String getScreenText() {
        // Privacy guard: block screen reading if full privacy or current app is blocked
        if (fullPrivacy) {
            return "{\"error\":\"Privacy mode is enabled — screen reading is disabled\"}";
        }
        if (blockedApps.contains(currentApp)) {
            return "{\"error\":\"This app is blocked by privacy settings — screen reading disabled\"}";
        }
        try {
            AccessibilityNodeInfo root = getRealAppRoot();
            if (root == null) {
                return "{\"error\":\"No active window content available\"}";
            }
            StringBuilder sb = new StringBuilder();
            List<AccessibilityNodeInfo> visited = new ArrayList<>();
            collectText(root, sb, visited, 0);
            String text = sb.toString().trim();
            if (text.isEmpty()) {
                return "{\"text\":\"\",\"error\":\"No text content found on screen\"}";
            }
            // Truncate to reasonable size for AI consumption
            if (text.length() > 8000) {
                text = text.substring(0, 8000) + "\n...(truncated)";
            }
            return "{\"text\":\"" + escapeJson(text) + "\"}";
        } catch (Exception e) {
            return "{\"error\":\"Failed to get screen text: " + e.getMessage() + "\"}";
        }
    }

    // Get the package name of the currently focused app (skips keyboard/IME)
    public String getActiveApp() {
        // Privacy guard: hide app info if full privacy
        if (fullPrivacy) {
            return "{\"privacy\":true,\"error\":\"Privacy mode is enabled — app info hidden\"}";
        }
        try {
            AccessibilityNodeInfo root = getRealAppRoot();
            if (root != null && root.getPackageName() != null) {
                String pkg = root.getPackageName().toString();
                String className = root.getClassName() != null ? root.getClassName().toString() : "";
                String label = getAppLabel(pkg);
                // Build app history string
                String historyStr = "";
                synchronized (appHistory) {
                    StringBuilder hs = new StringBuilder();
                    for (int i = 0; i < appHistory.size(); i++) {
                        if (i > 0) hs.append(", ");
                        hs.append(appHistory.get(i));
                    }
                    historyStr = hs.toString();
                }
                return "{\"package\":\"" + escapeJson(pkg) + "\",\"name\":\"" + escapeJson(label) + "\",\"class\":\"" + escapeJson(className) + "\",\"recent_apps\":\"" + escapeJson(historyStr) + "\"}";
            }
            // Fallback: use tracked currentApp
            if (!currentApp.isEmpty()) {
                return "{\"package\":\"" + escapeJson(currentApp) + "\",\"name\":\"" + escapeJson(currentAppLabel) + "\"}";
            }
            return "{\"error\":\"No active window\"}";
        } catch (Exception e) {
            return "{\"error\":\"Failed to get active app: " + e.getMessage() + "\"}";
        }
    }

    // Get app history as comma-separated string
    public static String getAppHistory() {
        synchronized (appHistory) {
            if (appHistory.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < appHistory.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(appHistory.get(i));
            }
            return sb.toString();
        }
    }

    public static java.util.LinkedList<String> getAppHistoryList() {
        synchronized (appHistory) {
            return new java.util.LinkedList<>(appHistory);
        }
    }

    // Click on the first element containing the given text
    public String clickText(String text) {
        try {
            AccessibilityNodeInfo root = getRealAppRoot();
            if (root == null) {
                return "{\"error\":\"No active window content available\"}";
            }
            AccessibilityNodeInfo target = findNodeByText(root, text);
            if (target == null) {
                return "{\"error\":\"Text not found on screen: " + escapeJson(text) + "\"}";
            }
            // Try to click — find clickable parent or the node itself
            AccessibilityNodeInfo clickable = findClickableParent(target);
            if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return "{\"status\":\"Clicked on text: " + escapeJson(text) + "\"}";
            }
            // Fallback: try to click the node itself
            if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return "{\"status\":\"Clicked on text: " + escapeJson(text) + "\"}";
            }
            return "{\"error\":\"Found text but could not click: " + escapeJson(text) + "\"}";
        } catch (Exception e) {
            return "{\"error\":\"Failed to click: " + e.getMessage() + "\"}";
        }
    }

    // Type text into the currently focused input field
    public String typeText(String text) {
        try {
            AccessibilityNodeInfo root = getRealAppRoot();
            if (root == null) {
                return "{\"error\":\"No active window content available\"}";
            }
            // Find a focused editable field
            AccessibilityNodeInfo focusable = findFocusedEditable(root);
            if (focusable == null) {
                return "{\"error\":\"No focused input field found on screen\"}";
            }
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            if (focusable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                return "{\"status\":\"Typed text into focused field\"}";
            }
            return "{\"error\":\"Could not type text into focused field\"}";
        } catch (Exception e) {
            return "{\"error\":\"Failed to type: " + e.getMessage() + "\"}";
        }
    }

    // Scroll the current screen
    public String scrollScreen(int direction) {
        try {
            AccessibilityNodeInfo root = getRealAppRoot();
            if (root == null) {
                return "{\"error\":\"No active window content available\"}";
            }
            AccessibilityNodeInfo scrollable = findScrollable(root);
            if (scrollable == null) {
                return "{\"error\":\"No scrollable container found on screen\"}";
            }
            int action = (direction == 0)
                ? AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                : AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
            if (scrollable.performAction(action)) {
                return "{\"status\":\"Scrolled " + (direction == 0 ? "up" : "down") + "\"}";
            }
            return "{\"error\":\"Scroll action failed\"}";
        } catch (Exception e) {
            return "{\"error\":\"Failed to scroll: " + e.getMessage() + "\"}";
        }
    }

    // Copy text to system clipboard
    public String copyToClipboard(String text) {
        try {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                getSystemService(CLIPBOARD_SERVICE);
            android.content.ClipData clip = android.content.ClipData.newPlainText("Argos", text);
            clipboard.setPrimaryClip(clip);
            return "{\"status\":\"Copied to clipboard\"}";
        } catch (Exception e) {
            return "{\"error\":\"Failed to copy: " + e.getMessage() + "\"}";
        }
    }

    // Paste clipboard text into the currently focused input field
    public String pasteText() {
        try {
            AccessibilityNodeInfo root = getRealAppRoot();
            if (root == null) {
                return "{\"error\":\"No active window content available\"}";
            }
            AccessibilityNodeInfo focusable = findFocusedEditable(root);
            if (focusable == null) {
                return "{\"error\":\"No focused input field found\"}";
            }
            // Try ACTION_PASTE first
            if (focusable.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                return "{\"status\":\"Pasted clipboard content into focused field\"}";
            }
            // Fallback: get clipboard text and use ACTION_SET_TEXT
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                getSystemService(CLIPBOARD_SERVICE);
            if (clipboard.hasPrimaryClip()) {
                android.content.ClipData.Item item = clipboard.getPrimaryClip().getItemAt(0);
                CharSequence clipText = item.getText();
                if (clipText != null) {
                    Bundle args = new Bundle();
                    args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, clipText);
                    if (focusable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                        return "{\"status\":\"Pasted clipboard text into focused field\"}";
                    }
                }
            }
            return "{\"error\":\"Could not paste into focused field\"}";
        } catch (Exception e) {
            return "{\"error\":\"Failed to paste: " + e.getMessage() + "\"}";
        }
    }

    // Copy text to clipboard and paste into focused field in one step
    public String copyAndPaste(String text) {
        String copyResult = copyToClipboard(text);
        try {
            org.json.JSONObject json = new org.json.JSONObject(copyResult);
            if (json.has("error")) return copyResult;
        } catch (Exception e) { return copyResult; }
        return pasteText();
    }

    // Open an app by package name, common name, OR installed app label
    // This works UNIVERSALLY — searches all installed apps on the device
    public String openApp(String name) {
        try {
            android.content.pm.PackageManager pm = getPackageManager();

            // Step 1: If it looks like a package name (has dots), try directly
            if (name.contains(".") && name.length() > 5) {
                Intent intent = pm.getLaunchIntentForPackage(name);
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    return "{\"status\":\"Opening " + escapeJson(name) + "\"}";
                }
            }

            // Step 2: Try the hardcoded common-name map first (fast path)
            String resolved = resolveAppPackage(name);
            if (!resolved.equals(name) || resolved.contains(".")) {
                Intent intent = pm.getLaunchIntentForPackage(resolved);
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    return "{\"status\":\"Opening " + escapeJson(resolved) + "\"}";
                }
            }

            // Step 3: UNIVERSAL SEARCH — search all installed apps by label
            String foundPkg = findInstalledAppByName(name);
            if (foundPkg != null) {
                Intent intent = pm.getLaunchIntentForPackage(foundPkg);
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    return "{\"status\":\"Opening " + escapeJson(foundPkg) + "\"}";
                }
            }

            return "{\"error\":\"App not found: " + escapeJson(name) + ". Use [TOOL:LIST_APPS] to see all installed apps.\"}";
        } catch (Exception e) {
            return "{\"error\":\"Failed to open app: " + e.getMessage() + "\"}";
        }
    }

    // Search all installed apps by fuzzy name matching
    // Returns the package name of the best match, or null if not found
    private String findInstalledAppByName(String query) {
        if (query == null || query.isEmpty()) return null;
        String q = query.toLowerCase().trim();

        try {
            android.content.pm.PackageManager pm = getPackageManager();
            // Get all launchable apps (apps that show in the app drawer)
            Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
            mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            // Use MATCH_ALL to get all apps including disabled ones
            java.util.List<android.content.pm.ResolveInfo> apps =
                pm.queryIntentActivities(mainIntent, android.content.pm.PackageManager.MATCH_ALL);

            // If launcher query returned nothing, try all installed packages
            if (apps == null || apps.isEmpty()) {
                java.util.List<android.content.pm.ApplicationInfo> allApps =
                    pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA);
                // Convert to a searchable list
                apps = new java.util.ArrayList<>();
                for (android.content.pm.ApplicationInfo ai : allApps) {
                    android.content.pm.ResolveInfo ri = new android.content.pm.ResolveInfo();
                    ri.activityInfo = new android.content.pm.ActivityInfo();
                    ri.activityInfo.applicationInfo = ai;
                    ri.activityInfo.packageName = ai.packageName;
                    ri.nonLocalizedLabel = pm.getApplicationLabel(ai);
                    apps.add(ri);
                }
            }

            String exactMatch = null;
            String startsWithMatch = null;
            String containsMatch = null;

            for (android.content.pm.ResolveInfo ri : apps) {
                String label = ri.loadLabel(pm).toString().toLowerCase();
                String pkg = ri.activityInfo.packageName;

                // Skip system UI packages
                if (pkg.startsWith("com.android.systemui") || pkg.startsWith("com.android.phone")) {
                    continue;
                }

                // Exact label match (highest priority)
                if (label.equals(q)) {
                    return pkg;
                }
                // Package name exact match
                if (pkg.equals(q)) {
                    return pkg;
                }
                // Label starts with query
                if (startsWithMatch == null && label.startsWith(q)) {
                    startsWithMatch = pkg;
                }
                // Query starts with label
                if (startsWithMatch == null && q.startsWith(label) && label.length() > 2) {
                    startsWithMatch = pkg;
                }
                // Label contains query
                if (containsMatch == null && label.contains(q) && q.length() > 2) {
                    containsMatch = pkg;
                }
                // Package name contains query
                if (containsMatch == null && pkg.contains(q) && q.length() > 2) {
                    containsMatch = pkg;
                }
            }

            if (exactMatch != null) return exactMatch;
            if (startsWithMatch != null) return startsWithMatch;
            if (containsMatch != null) return containsMatch;
        } catch (Exception e) {
            android.util.Log.w("Argos", "findInstalledAppByName failed: " + e.getMessage());
        }
        return null;
    }

    // List all installed launchable apps — returns JSON array of {name, package}
    public String listInstalledApps() {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
            mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            java.util.List<android.content.pm.ResolveInfo> apps =
                pm.queryIntentActivities(mainIntent, 0);

            // Sort alphabetically by app label
            apps.sort((a, b) -> {
                String la = a.loadLabel(pm).toString();
                String lb = b.loadLabel(pm).toString();
                return la.compareToIgnoreCase(lb);
            });

            org.json.JSONArray arr = new org.json.JSONArray();
            for (android.content.pm.ResolveInfo ri : apps) {
                String label = ri.loadLabel(pm).toString();
                String pkg = ri.activityInfo.packageName;
                org.json.JSONObject obj = new org.json.JSONObject();
                obj.put("name", label);
                obj.put("package", pkg);
                arr.put(obj);
            }

            return arr.toString();
        } catch (Exception e) {
            return "{\"error\":\"Failed to list apps: " + escapeJson(e.getMessage()) + "\"}";
        }
    }

    // Map common app names to package names so the AI can say "open whatsapp"
    private String resolveAppPackage(String name) {
        if (name == null) return "";
        String lower = name.toLowerCase().trim();
        // If it already looks like a package (contains dots), return as-is
        if (lower.contains(".") && lower.length() > 5) return name;

        switch (lower) {
            // Messaging
            case "messages": case "message": case "sms": case "text":
                return "com.google.android.apps.messaging";
            case "whatsapp": return "com.whatsapp";
            case "telegram": return "org.telegram.messenger";
            case "messenger": case "facebook messenger": return "com.facebook.orca";
            case "discord": return "com.discord";
            case "signal": return "org.thoughtcrime.securesms";
            case "slack": return "com.slack";
            case "teams": case "microsoft teams": return "com.microsoft.teams";
            // Browser
            case "browser": case "web browser": case "internet":
                return "com.android.chrome";
            case "chrome": return "com.android.chrome";
            case "firefox": return "org.mozilla.firefox";
            case "edge": case "microsoft edge": return "com.microsoft.emmx";
            case "opera": return "com.opera.browser";
            case "brave": return "com.brave.browser";
            // Social
            case "facebook": case "fb": return "com.facebook.katana";
            case "instagram": case "insta": return "com.instagram.android";
            case "twitter": case "x": return "com.twitter.android";
            case "tiktok": return "com.zhiliaoapp.musically";
            case "snapchat": return "com.snapchat.android";
            case "linkedin": return "com.linkedin.android";
            case "reddit": return "com.reddit.frontpage";
            case "pinterest": return "com.pinterest";
            // Email
            case "gmail": case "email": case "mail": return "com.google.android.gm";
            case "outlook": return "com.microsoft.office.outlook";
            // Media
            case "youtube": return "com.google.android.youtube";
            case "spotify": return "com.spotify.music";
            case "netflix": return "com.netflix.mediaclient";
            case "music": case "play music": return "com.google.android.apps.youtube.music";
            case "photos": case "gallery": return "com.google.android.apps.photos";
            case "camera": return "com.android.camera";
            // Productivity
            case "calendar": return "com.google.android.calendar";
            case "maps": case "google maps": return "com.google.android.apps.maps";
            case "drive": case "google drive": return "com.google.android.apps.docs";
            case "docs": case "google docs": return "com.google.android.apps.docs.editors.docs";
            case "sheets": case "google sheets": return "com.google.android.apps.docs.editors.sheets";
            case "notes": case "keep": case "google keep": return "com.google.android.keep";
            case "calculator": return "com.android.calculator2";
            case "clock": case "alarm": return "com.google.android.deskclock";
            case "settings": return "com.android.settings";
            case "phone": case "dialer": case "call": return "com.google.android.dialer";
            case "contacts": return "com.android.contacts";
            case "files": case "file manager": return "com.android.documentsui";
            case "weather": return "com.google.android.apps.weather";
            case "news": case "google news": return "com.google.android.apps.magazines";
            case "translate": case "google translate": return "com.google.android.apps.translate";
            case "play store": case "play": return "com.android.vending";
            case "zoom": return "us.zoom.videomeetings";
            default:
                // Try prefixing with com. as a last resort
                return name;
        }
    }

    // ── Helper methods ──

    private void collectText(AccessibilityNodeInfo node, StringBuilder sb,
                             List<AccessibilityNodeInfo> visited, int depth) {
        if (node == null || visited.contains(node)) return;
        visited.add(node);

        CharSequence text = node.getText();
        if (text != null && text.length() > 0) {
            String t = text.toString().trim();
            if (!t.isEmpty()) {
                sb.append(t).append("\n");
            }
        }
        CharSequence desc = node.getContentDescription();
        if (desc != null && desc.length() > 0) {
            String d = desc.toString().trim();
            if (!d.isEmpty() && (text == null || !d.equals(text.toString().trim()))) {
                sb.append("[desc: ").append(d).append("]\n");
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectText(child, sb, visited, depth + 1);
            }
        }
    }

    private AccessibilityNodeInfo findNodeByText(AccessibilityNodeInfo node, String text) {
        if (node == null) return null;
        CharSequence nodeText = node.getText();
        if (nodeText != null && nodeText.toString().toLowerCase().contains(text.toLowerCase())) {
            return node;
        }
        // Also search content description
        CharSequence desc = node.getContentDescription();
        if (desc != null && desc.toString().toLowerCase().contains(text.toLowerCase())) {
            return node;
        }
        // Use built-in text search
        List<AccessibilityNodeInfo> matches = node.findAccessibilityNodeInfosByText(text);
        if (matches != null && !matches.isEmpty()) {
            return matches.get(0);
        }
        // Recurse children
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo found = findNodeByText(child, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findClickableParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        while (current != null) {
            if (current.isClickable()) return current;
            current = current.getParent();
        }
        return null;
    }

    private AccessibilityNodeInfo findFocusedEditable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isEditable() && node.isFocused()) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo found = findFocusedEditable(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isScrollable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo found = findScrollable(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private String escapeJson(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:   sb.append(c);
            }
        }
        return sb.toString();
    }

    // ── UI Tree Inspection ──
    // Node ID counter for each inspection session
    private int nodeIdCounter = 0;
    private java.util.Map<Integer, AccessibilityNodeInfo> nodeMap = new java.util.HashMap<>();

    // Build a JSON tree of all UI elements. Called from C++ via FloatingRobotService.
    public String getUITree(int maxDepth) {
        // Reset node map for this session
        synchronized (nodeMap) {
            nodeMap.clear();
            nodeIdCounter = 0;
        }

        AccessibilityNodeInfo root = getRealAppRoot();
        if (root == null) {
            return "{\"error\":\"No active window content available\"}";
        }

        String appName = currentAppLabel != null ? currentAppLabel : "";
        String pkg = root.getPackageName() != null ? root.getPackageName().toString() : "";

        StringBuilder sb = new StringBuilder();
        sb.append("{\"app\":\"").append(escapeJson(appName)).append("\"");
        sb.append(",\"package\":\"").append(escapeJson(pkg)).append("\"");
        sb.append(",\"elements\":[");

        buildTreeJson(root, sb, 0, maxDepth);

        sb.append("]}");
        return sb.toString();
    }

    private void buildTreeJson(AccessibilityNodeInfo node, StringBuilder sb, int depth, int maxDepth) {
        if (node == null) return;
        if (maxDepth >= 0 && depth > maxDepth) return;

        // Skip nodes with no text, no desc, no children, and not clickable (noise reduction)
        // But always include root and first-level children
        if (depth > 1) {
            CharSequence text = node.getText();
            CharSequence desc = node.getContentDescription();
            boolean hasContent = (text != null && text.length() > 0) || (desc != null && desc.length() > 0);
            boolean isInteractive = node.isClickable() || node.isLongClickable() || node.isEditable() || node.isScrollable();
            if (!hasContent && !isInteractive && node.getChildCount() == 0) return;
        }

        int id;
        synchronized (nodeMap) {
            id = nodeIdCounter++;
            nodeMap.put(id, node);
        }

        if (id > 0) sb.append(",");

        sb.append("{\"id\":").append(id);

        CharSequence text = node.getText();
        if (text != null && text.length() > 0) {
            sb.append(",\"text\":\"").append(escapeJson(text.toString().trim())).append("\"");
        }

        CharSequence desc = node.getContentDescription();
        if (desc != null && desc.length() > 0) {
            sb.append(",\"desc\":\"").append(escapeJson(desc.toString().trim())).append("\"");
        }

        String role = inferRole(node);
        if (!role.isEmpty()) {
            sb.append(",\"role\":\"").append(escapeJson(role)).append("\"");
        }

        String resId = node.getViewIdResourceName();
        if (resId != null && !resId.isEmpty()) {
            sb.append(",\"resId\":\"").append(escapeJson(resId)).append("\"");
        }

        sb.append(",\"clickable\":").append(node.isClickable());
        sb.append(",\"longClickable\":").append(node.isLongClickable());
        sb.append(",\"editable\":").append(node.isEditable());
        sb.append(",\"focused\":").append(node.isFocused());
        sb.append(",\"scrollable\":").append(node.isScrollable());

        if (node.isChecked()) sb.append(",\"checked\":true");
        if (!node.isEnabled()) sb.append(",\"enabled\":false");

        // Bounds
        android.graphics.Rect bounds = new android.graphics.Rect();
        node.getBoundsInScreen(bounds);
        sb.append(",\"bounds\":{\"x\":").append(bounds.left)
          .append(",\"y\":").append(bounds.top)
          .append(",\"w\":").append(bounds.width())
          .append(",\"h\":").append(bounds.height()).append("}");

        // Children
        if (node.getChildCount() > 0) {
            sb.append(",\"children\":[");
            boolean firstChild = true;
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child == null) continue;
                // Build child into a temp buffer to check if it produced output
                int sbLen = sb.length();
                buildTreeJson(child, sb, depth + 1, maxDepth);
                // If nothing was added, undo the potential comma
                if (sb.length() == sbLen && !firstChild) {
                    // Remove trailing comma if child produced nothing
                    // This is handled by the id>0 check in recursive call
                }
                firstChild = false;
            }
            sb.append("]");
        }

        sb.append("}");
    }

    // Infer a simplified role from the node's class name
    private String inferRole(AccessibilityNodeInfo node) {
        String cls = node.getClassName() != null ? node.getClassName().toString() : "";
        String lower = cls.toLowerCase();

        if (lower.contains("button")) return "button";
        if (lower.contains("imagebutton")) return "button";
        if (lower.contains("checkbox")) return "checkbox";
        if (lower.contains("radiobutton")) return "radio";
        if (lower.contains("switch")) return "switch";
        if (lower.contains("toggle")) return "toggle";
        if (lower.contains("edittext") || lower.contains("textfield")) return "input";
        if (lower.contains("textview") || lower.contains("text")) return "text";
        if (lower.contains("imageview") || lower.contains("image")) return "image";
        if (lower.contains("recyclerview") || lower.contains("listview") || lower.contains("scrollview")) return "list";
        if (lower.contains("webview")) return "webview";
        if (lower.contains("progressbar")) return "progress";
        if (lower.contains("spinner")) return "dropdown";
        if (lower.contains("toolbar") || lower.contains("actionbar")) return "toolbar";
        if (lower.contains("menu")) return "menu";
        if (lower.contains("tab")) return "tab";
        if (lower.contains("card")) return "card";
        if (lower.contains("container") || lower.contains("layout") || lower.contains("group")) return "container";
        if (lower.contains("framelayout")) return "container";
        if (lower.contains("linearlayout")) return "container";
        if (lower.contains("relativelayout")) return "container";
        if (lower.contains("constraintlayout")) return "container";
        return "";
    }

    // Perform an accessibility action on a node by its session ID
    public String performUIAction(int elementId, String action, String extra) {
        AccessibilityNodeInfo node;
        synchronized (nodeMap) {
            node = nodeMap.get(elementId);
        }
        if (node == null) {
            return "{\"error\":\"Element not found (id=" + elementId + "). Run ui_inspect first.\"}";
        }

        // Refresh the node to get the latest state (nodes become stale after UI changes)
        node.refresh();

        // Find clickable parent if the node itself isn't clickable
        AccessibilityNodeInfo target = node;
        if (!node.isClickable() && ("click".equals(action) || "long_click".equals(action))) {
            AccessibilityNodeInfo clickable = findClickableParent(node);
            if (clickable != null) target = clickable;
        }

        boolean success = false;
        String actionName = action;

        switch (action) {
            case "click":
                success = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                break;
            case "long_click":
                success = target.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);
                break;
            case "focus":
                success = target.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                break;
            case "select":
                success = target.performAction(AccessibilityNodeInfo.ACTION_SELECT);
                break;
            case "clear_selection":
                success = target.performAction(AccessibilityNodeInfo.ACTION_CLEAR_SELECTION);
                break;
            case "scroll_forward":
                success = target.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                break;
            case "scroll_backward":
                success = target.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
                break;
            case "expand":
                success = target.performAction(AccessibilityNodeInfo.ACTION_EXPAND);
                break;
            case "collapse":
                success = target.performAction(AccessibilityNodeInfo.ACTION_COLLAPSE);
                break;
            case "set_text":
                Bundle args = new Bundle();
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, extra);
                success = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                actionName = "set_text: " + extra;
                break;
            case "set_selection":
                // extra format: "start,end"
                try {
                    String[] parts = extra.split(",");
                    int start = Integer.parseInt(parts[0].trim());
                    int end = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : start;
                    Bundle selArgs = new Bundle();
                    selArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start);
                    selArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end);
                    success = target.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs);
                } catch (Exception e) {
                    return "{\"error\":\"Invalid selection args: " + escapeJson(extra) + "\"}";
                }
                break;
            default:
                return "{\"error\":\"Unknown action: " + escapeJson(action) + "\"}";
        }

        if (success) {
            return "{\"status\":\"success\",\"action\":\"" + escapeJson(actionName) + "\",\"elementId\":" + elementId + "}";
        } else {
            return "{\"status\":\"failed\",\"action\":\"" + escapeJson(actionName) + "\",\"elementId\":" + elementId + "}";
        }
    }

    // ── Screenshot ──
    // Takes a screenshot using PixelCopy API (requires API 24+)

    // Get screen context for AI — captures screenshot + OCR + app info
    // This is called automatically before the AI responds so it knows
    // what the user is looking at. Respects privacy settings.
    // Returns a compact JSON string with screen context, or empty if privacy-blocked.
    public String getScreenContextForAI() {
        try {
            // Privacy checks
            if (fullPrivacy) {
                return "{\"context\":\"none\",\"reason\":\"full_privacy\"}";
            }
            if (blockedApps.contains(currentApp)) {
                return "{\"context\":\"none\",\"reason\":\"app_blocked\",\"app\":\"" +
                    escapeJson(currentAppLabel != null ? currentAppLabel : "") + "\"}";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("{\"context\":\"screen\",\"app\":\"");
            sb.append(escapeJson(currentAppLabel != null ? currentAppLabel : "unknown"));
            sb.append("\",\"package\":\"");
            sb.append(escapeJson(currentApp != null ? currentApp : ""));
            sb.append("\",");

            // Get screen text (fast — uses accessibility tree, no screenshot needed)
            try {
                AccessibilityNodeInfo root = getRealAppRoot();
                if (root != null) {
                    StringBuilder textBuilder = new StringBuilder();
                    List<AccessibilityNodeInfo> visited = new ArrayList<>();
                    collectText(root, textBuilder, visited, 0);
                    String text = textBuilder.toString().trim();
                    // Truncate to 500 chars to keep context compact
                    if (text.length() > 500) {
                        text = text.substring(0, 500) + "...";
                    }
                    sb.append("\"visible_text\":\"");
                    sb.append(escapeJson(text));
                    sb.append("\",");
                }
            } catch (Exception e) {
                sb.append("\"visible_text\":\"\",");
            }

            // Get app history (recently used apps)
            sb.append("\"recent_apps\":\"");
            sb.append(escapeJson(getAppHistoryString()));
            sb.append("\",");

            // Try to take a real screenshot + OCR (API 30+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    String ocrResult = observeScreen();
                    if (ocrResult != null && ocrResult.contains("\"status\":\"success\"")) {
                        // Extract just the full_text from OCR to keep it compact
                        int textIdx = ocrResult.indexOf("\"full_text\":\"");
                        if (textIdx >= 0) {
                            int textStart = textIdx + 13;
                            int textEnd = ocrResult.indexOf("\"", textStart);
                            // Find the closing quote (handle escaped quotes)
                            while (textEnd > 0 && ocrResult.charAt(textEnd - 1) == '\\') {
                                textEnd = ocrResult.indexOf("\"", textEnd + 1);
                            }
                            if (textEnd > textStart) {
                                String ocrText = ocrResult.substring(textStart, textEnd);
                                if (ocrText.length() > 800) {
                                    ocrText = ocrText.substring(0, 800) + "...";
                                }
                                sb.append("\"ocr_text\":\"");
                                sb.append(escapeJson(ocrText));
                                sb.append("\",");
                            }
                        }
                    }
                } catch (Exception e) {
                    // OCR failed — that's ok, we still have visible_text
                }
            }

            // Get clickable elements (buttons, links the user can interact with)
            try {
                String elements = getClickableElements();
                if (elements != null && elements.length() > 2) {
                    // Truncate to keep compact
                    if (elements.length() > 500) {
                        elements = elements.substring(0, 500) + "...]";
                    }
                    sb.append("\"clickable_elements\":");
                    sb.append(elements);
                    sb.append(",");
                }
            } catch (Exception e) {}

            // Remove trailing comma
            String result = sb.toString();
            if (result.endsWith(",")) {
                result = result.substring(0, result.length() - 1);
            }
            result += "}";

            return result;
        } catch (Exception e) {
            return "{\"context\":\"error\",\"error\":\"" + escapeJson(e.getMessage()) + "\"}";
        }
    }

    // Get app history as a string (recently used apps)
    private String getAppHistoryString() {
        if (appHistory.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < appHistory.size() && i < 5; i++) {
            if (i > 0) sb.append(", ");
            String pkg = appHistory.get(i);
            String label = getAppLabel(pkg);
            sb.append(label != null ? label : pkg);
        }
        return sb.toString();
    }

    public String takeScreenshot(String savePath) {
        try {
            // Use the root view of the top window
            AccessibilityNodeInfo root = getRealAppRoot();
            if (root == null) {
                return "{\"error\":\"No active window for screenshot\"}";
            }

            android.graphics.Rect bounds = new android.graphics.Rect();
            root.getBoundsInScreen(bounds);

            if (bounds.width() <= 0 || bounds.height() <= 0) {
                return "{\"error\":\"Invalid bounds for screenshot\"}";
            }

            // Create a bitmap
            android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(
                bounds.width(), bounds.height(), android.graphics.Bitmap.Config.ARGB_8888);

            // Try PixelCopy (API 24+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // We need a Surface — use the accessibility windows
                java.util.List<AccessibilityWindowInfo> windows = getWindows();
                if (windows == null || windows.isEmpty()) {
                    return "{\"error\":\"No windows available for screenshot\"}";
                }

                // Find the application window
                android.view.Surface surface = null;
                for (AccessibilityWindowInfo win : windows) {
                    if (win.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) {
                        // PixelCopy needs a Surface, but Accessibility doesn't expose it
                        // Fall back to using MediaProjection (requires activity result)
                        break;
                    }
                }
            }

            // Fallback: save the bounds info and note that screenshot requires MediaProjection
            // For now, return a descriptive JSON of what's on screen
            String screenText = getScreenText();
            String appName = currentAppLabel != null ? currentAppLabel : "unknown";

            // Save screen text as a "screenshot" file for OCR-like analysis
            if (savePath == null || savePath.isEmpty()) {
                savePath = getCacheDir().getAbsolutePath() + "/argos_screenshot.txt";
            }
            java.io.FileWriter fw = new java.io.FileWriter(savePath);
            fw.write("App: " + appName + "\n");
            fw.write("Bounds: " + bounds.toString() + "\n");
            fw.write("Timestamp: " + System.currentTimeMillis() + "\n");
            fw.write("--- Screen Content ---\n");
            fw.write(screenText);
            fw.close();

            return "{\"status\":\"saved\",\"path\":\"" + escapeJson(savePath) + "\",\"app\":\"" + escapeJson(appName) + "\",\"bounds\":\"" + bounds.toString() + "\",\"note\":\"Text-based screenshot saved. For image screenshot, MediaProjection permission is required.\"}";
        } catch (Exception e) {
            return "{\"error\":\"Screenshot failed: " + escapeJson(e.getMessage()) + "\"}";
        }
    }

    // ── Real Screenshot + OCR ──
    // Takes a real screenshot using AccessibilityService.takeScreenshot (API 30+)
    // Then runs ML Kit OCR on it and returns text with bounding boxes
    public String observeScreen() {
        // Privacy guard: block screenshots if full privacy or current app is blocked
        if (fullPrivacy) {
            return "{\"privacy\":true,\"error\":\"Privacy mode is enabled — screenshot disabled\"}";
        }
        if (blockedApps.contains(currentApp)) {
            return "{\"privacy\":true,\"error\":\"This app is blocked by privacy settings — screenshot disabled\",\"app\":\"" + escapeJson(currentAppLabel != null ? currentAppLabel : "") + "\"}";
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // API < 30: fallback to text-based screenshot
            return takeScreenshot("");
        }

        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final StringBuilder result = new StringBuilder();
        final android.graphics.Bitmap[] capturedBitmap = {null};

        takeScreenshot(android.view.Display.DEFAULT_DISPLAY,
            getMainExecutor(),
            new AccessibilityService.TakeScreenshotCallback() {
                @Override
                public void onSuccess(AccessibilityService.ScreenshotResult screenshot) {
                    try {
                        android.graphics.Bitmap bitmap = android.graphics.Bitmap.wrapHardwareBuffer(
                            screenshot.getHardwareBuffer(), screenshot.getColorSpace());
                        screenshot.getHardwareBuffer().close();
                        if (bitmap != null) {
                            // Convert to software bitmap for ML Kit
                            capturedBitmap[0] = bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false);
                            bitmap.recycle();
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Screenshot bitmap error: " + e.getMessage());
                    }
                    latch.countDown();
                }

                @Override
                public void onFailure(int errorCode) {
                    Log.e(TAG, "Screenshot failed: " + errorCode);
                    latch.countDown();
                }
            });

        try {
            latch.await(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            return "{\"error\":\"Screenshot timeout\"}";
        }

        if (capturedBitmap[0] == null) {
            return "{\"error\":\"Screenshot capture failed\"}";
        }

        // Run OCR on the bitmap
        return runOcrOnBitmap(capturedBitmap[0]);
    }

    // Run ML Kit OCR on a bitmap and return text with bounding boxes
    private String runOcrOnBitmap(android.graphics.Bitmap bitmap) {
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final StringBuilder ocrResult = new StringBuilder();
        final StringBuilder errorMsg = new StringBuilder();

        com.google.mlkit.vision.text.TextRecognizer recognizer =
            com.google.mlkit.vision.text.TextRecognition.getClient(
                com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS);

        com.google.mlkit.vision.common.InputImage image =
            com.google.mlkit.vision.common.InputImage.fromBitmap(bitmap, 0);

        recognizer.process(image)
            .addOnSuccessListener(visionText -> {
                ocrResult.append("{\"status\":\"success\",\"ocr\":{");
                ocrResult.append("\"full_text\":\"").append(escapeJson(visionText.getText())).append("\",");
                ocrResult.append("\"blocks\":[");

                boolean firstBlock = true;
                for (com.google.mlkit.vision.text.Text.TextBlock block : visionText.getTextBlocks()) {
                    if (!firstBlock) ocrResult.append(",");
                    firstBlock = false;
                    android.graphics.Rect bb = block.getBoundingBox();
                    ocrResult.append("{\"text\":\"").append(escapeJson(block.getText())).append("\"");
                    if (bb != null) {
                        ocrResult.append(",\"bounds\":{\"left\":").append(bb.left)
                            .append(",\"top\":").append(bb.top)
                            .append(",\"right\":").append(bb.right)
                            .append(",\"bottom\":").append(bb.bottom)
                            .append(",\"centerX\":").append(bb.centerX())
                            .append(",\"centerY\":").append(bb.centerY()).append("}");
                    }
                    ocrResult.append("}");
                }
                ocrResult.append("]}");

                // Also include UI elements for tappable targets
                ocrResult.append(",\"ui_elements\":").append(getClickableElements());
                ocrResult.append(",\"app\":\"").append(escapeJson(currentAppLabel != null ? currentAppLabel : "unknown")).append("\"");
                ocrResult.append("}");
                latch.countDown();
            })
            .addOnFailureListener(e -> {
                errorMsg.append(e.getMessage());
                latch.countDown();
            });

        try {
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            return "{\"error\":\"OCR timeout\"}";
        }

        if (errorMsg.length() > 0) {
            return "{\"error\":\"OCR failed: " + escapeJson(errorMsg.toString()) + "\"}";
        }

        bitmap.recycle();
        return ocrResult.toString();
    }

    // ── Notifications ──
    // Get active notifications by opening the notification shade and reading it
    public String getNotifications() {
        try {
            // Open notification shade
            boolean opened = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);

            // Wait for shade to open
            try { Thread.sleep(1500); } catch (Exception e) {}

            // Read the notification shade content
            String notifText = getScreenText();

            // Also get the UI tree for structured data
            String treeJson = getUITree(10);

            // Close the shade
            performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);

            return "{\"source\":\"notification_shade\",\"opened\":" + opened
                + ",\"text\":\"" + escapeJson(notifText) + "\""
                + ",\"tree\":" + treeJson + "}";
        } catch (Exception e) {
            try { performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS); } catch (Exception ex) {}
            return "{\"error\":\"Failed to get notifications: " + escapeJson(e.getMessage()) + "\"}";
        }
    }

    // Reply to a notification by reading the shade, finding the reply field, and typing
    public String replyToNotification(int index, String message) {
        try {
            // Open notification shade
            performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
            try { Thread.sleep(1500); } catch (Exception e) {}

            // Get the UI tree to find reply buttons and input fields
            String treeJson = getUITree(10);

            // Find "Reply" button by text
            AccessibilityNodeInfo root = getRealAppRoot();
            if (root == null) {
                performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
                return "{\"error\":\"Could not read notification shade\"}";
            }

            // Find all "Reply" buttons
            java.util.List<AccessibilityNodeInfo> replyButtons = root.findAccessibilityNodeInfosByText("Reply");
            if (replyButtons == null || replyButtons.isEmpty()) {
                // Try "reply" lowercase
                replyButtons = root.findAccessibilityNodeInfosByText("reply");
            }

            if (replyButtons == null || replyButtons.isEmpty()) {
                performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
                return "{\"error\":\"No reply buttons found in notification shade\"}";
            }

            // Select the notification by index (0-based)
            if (index >= replyButtons.size()) {
                performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
                return "{\"error\":\"Notification index " + index + " out of range (found " + replyButtons.size() + " reply buttons)\"}";
            }

            AccessibilityNodeInfo replyBtn = replyButtons.get(index);

            // Click the reply button to expand the input field
            AccessibilityNodeInfo clickable = findClickableParent(replyBtn);
            if (clickable != null) {
                clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            } else {
                replyBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }

            // Wait for input field to appear
            try { Thread.sleep(1000); } catch (Exception e) {}

            // Re-read the tree to find the now-visible input field
            root = getRealAppRoot();
            if (root != null) {
                AccessibilityNodeInfo editField = findFocusedEditable(root);
                if (editField == null) {
                    // Try to find any editable field
                    editField = findAnyEditable(root);
                }

                if (editField != null) {
                    // Type the message
                    Bundle args = new Bundle();
                    args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message);
                    editField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);

                    // Wait briefly then find and click Send button
                    try { Thread.sleep(500); } catch (Exception e) {}

                    root = getRealAppRoot();
                    if (root != null) {
                        java.util.List<AccessibilityNodeInfo> sendButtons = root.findAccessibilityNodeInfosByText("Send");
                        if (sendButtons != null && !sendButtons.isEmpty()) {
                            AccessibilityNodeInfo sendBtn = sendButtons.get(0);
                            AccessibilityNodeInfo sendClickable = findClickableParent(sendBtn);
                            if (sendClickable != null) {
                                sendClickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                            } else {
                                sendBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                            }
                        }
                    }

                    // Close notification shade
                    performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
                    return "{\"status\":\"success\",\"message\":\"Replied to notification " + index + " with: " + escapeJson(message) + "\"}";
                }
            }

            // Close notification shade
            performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
            return "{\"error\":\"Could not find input field after clicking reply\"}";
        } catch (Exception e) {
            try { performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS); } catch (Exception ex) {}
            return "{\"error\":\"Reply failed: " + escapeJson(e.getMessage()) + "\"}";
        }
    }

    // Find any editable field in the tree
    private AccessibilityNodeInfo findAnyEditable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isEditable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo found = findAnyEditable(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    // ── Gesture-based actions (dispatchGesture) ──
    // These work on ANY screen coordinate, even for apps with no accessibility nodes (canvas, games, etc.)

    // Tap (click) at a specific screen coordinate
    public String clickAtPoint(int x, int y) {
        try {
            android.graphics.Path path = new android.graphics.Path();
            path.moveTo(x, y);
            path.lineTo(x + 1, y); // non-zero path required

            android.accessibilityservice.GestureDescription.StrokeDescription stroke =
                new android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 50);
            android.accessibilityservice.GestureDescription.Builder builder =
                new android.accessibilityservice.GestureDescription.Builder();
            builder.addStroke(stroke);

            final boolean[] completed = {false};
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

            dispatchGesture(builder.build(), new GestureResultCallback() {
                @Override
                public void onCompleted(android.accessibilityservice.GestureDescription gesture) {
                    completed[0] = true;
                    latch.countDown();
                }
                @Override
                public void onCancelled(android.accessibilityservice.GestureDescription gesture) {
                    latch.countDown();
                }
            }, new android.os.Handler(getMainLooper()));

            latch.await(2, java.util.concurrent.TimeUnit.SECONDS);

            if (completed[0]) {
                return "{\"status\":\"tapped\",\"x\":" + x + ",\"y\":" + y + "}";
            }
            return "{\"error\":\"tap gesture cancelled at (" + x + "," + y + ")\"}";
        } catch (Exception e) {
            return "{\"error\":\"tap failed: " + escapeJson(e.getMessage()) + "\"}";
        }
    }

    // Long press at a specific screen coordinate
    public String longPressAtPoint(int x, int y) {
        try {
            android.graphics.Path path = new android.graphics.Path();
            path.moveTo(x, y);
            path.lineTo(x + 1, y);

            // Long press duration: 3x the system long press timeout
            int longPressTime = android.view.ViewConfiguration.getLongPressTimeout() * 3;

            android.accessibilityservice.GestureDescription.StrokeDescription stroke =
                new android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, longPressTime);
            android.accessibilityservice.GestureDescription.Builder builder =
                new android.accessibilityservice.GestureDescription.Builder();
            builder.addStroke(stroke);

            final boolean[] completed = {false};
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

            dispatchGesture(builder.build(), new GestureResultCallback() {
                @Override
                public void onCompleted(android.accessibilityservice.GestureDescription gesture) {
                    completed[0] = true;
                    latch.countDown();
                }
                @Override
                public void onCancelled(android.accessibilityservice.GestureDescription gesture) {
                    latch.countDown();
                }
            }, new android.os.Handler(getMainLooper()));

            latch.await(longPressTime + 2000, java.util.concurrent.TimeUnit.MILLISECONDS);

            if (completed[0]) {
                return "{\"status\":\"long_pressed\",\"x\":" + x + ",\"y\":" + y + "}";
            }
            return "{\"error\":\"long press gesture cancelled at (" + x + "," + y + ")\"}";
        } catch (Exception e) {
            return "{\"error\":\"long press failed: " + escapeJson(e.getMessage()) + "\"}";
        }
    }

    // Swipe from one point to another with a given duration
    public String swipe(int x1, int y1, int x2, int y2, int durationMs) {
        try {
            if (durationMs < 50) durationMs = 300; // minimum reasonable swipe time

            android.graphics.Path path = new android.graphics.Path();
            path.moveTo(x1, y1);
            path.lineTo(x2, y2);

            android.accessibilityservice.GestureDescription.StrokeDescription stroke =
                new android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, durationMs);
            android.accessibilityservice.GestureDescription.Builder builder =
                new android.accessibilityservice.GestureDescription.Builder();
            builder.addStroke(stroke);

            final boolean[] completed = {false};
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

            dispatchGesture(builder.build(), new GestureResultCallback() {
                @Override
                public void onCompleted(android.accessibilityservice.GestureDescription gesture) {
                    completed[0] = true;
                    latch.countDown();
                }
                @Override
                public void onCancelled(android.accessibilityservice.GestureDescription gesture) {
                    latch.countDown();
                }
            }, new android.os.Handler(getMainLooper()));

            latch.await(durationMs + 2000, java.util.concurrent.TimeUnit.MILLISECONDS);

            if (completed[0]) {
                return "{\"status\":\"swiped\",\"from\":{\"x\":" + x1 + ",\"y\":" + y1 + "},\"to\":{\"x\":" + x2 + ",\"y\":" + y2 + "},\"duration\":" + durationMs + "}";
            }
            return "{\"error\":\"swipe gesture cancelled\"}";
        } catch (Exception e) {
            return "{\"error\":\"swipe failed: " + escapeJson(e.getMessage()) + "\"}";
        }
    }

    // Convenience: swipe up (scroll down) at center of screen
    public String swipeUp() {
        android.graphics.Point size = new android.graphics.Point();
        android.view.Display display = getSystemService(android.content.Context.WINDOW_SERVICE) != null
            ? ((android.view.WindowManager) getSystemService(android.content.Context.WINDOW_SERVICE)).getDefaultDisplay()
            : null;
        if (display != null) display.getSize(size);
        int w = size.x > 0 ? size.x : 1080;
        int h = size.y > 0 ? size.y : 1920;
        return swipe(w / 2, h * 3 / 4, w / 2, h / 4, 400);
    }

    // Convenience: swipe down (scroll up) at center of screen
    public String swipeDown() {
        android.graphics.Point size = new android.graphics.Point();
        android.view.Display display = getSystemService(android.content.Context.WINDOW_SERVICE) != null
            ? ((android.view.WindowManager) getSystemService(android.content.Context.WINDOW_SERVICE)).getDefaultDisplay()
            : null;
        if (display != null) display.getSize(size);
        int w = size.x > 0 ? size.x : 1080;
        int h = size.y > 0 ? size.y : 1920;
        return swipe(w / 2, h / 4, w / 2, h * 3 / 4, 400);
    }

    // Convenience: swipe left (scroll right) at center of screen
    public String swipeLeft() {
        android.graphics.Point size = new android.graphics.Point();
        android.view.Display display = getSystemService(android.content.Context.WINDOW_SERVICE) != null
            ? ((android.view.WindowManager) getSystemService(android.content.Context.WINDOW_SERVICE)).getDefaultDisplay()
            : null;
        if (display != null) display.getSize(size);
        int w = size.x > 0 ? size.x : 1080;
        int h = size.y > 0 ? size.y : 1920;
        return swipe(w * 3 / 4, h / 2, w / 4, h / 2, 400);
    }

    // Convenience: swipe right (scroll left) at center of screen
    public String swipeRight() {
        android.graphics.Point size = new android.graphics.Point();
        android.view.Display display = getSystemService(android.content.Context.WINDOW_SERVICE) != null
            ? ((android.view.WindowManager) getSystemService(android.content.Context.WINDOW_SERVICE)).getDefaultDisplay()
            : null;
        if (display != null) display.getSize(size);
        int w = size.x > 0 ? size.x : 1080;
        int h = size.y > 0 ? size.y : 1920;
        return swipe(w / 4, h / 2, w * 3 / 4, h / 2, 400);
    }

    // Find the smallest (most specific) accessibility node at a screen point
    public AccessibilityNodeInfo findNodeAtPoint(AccessibilityNodeInfo root, int x, int y) {
        if (root == null) return null;
        android.graphics.Rect bounds = new android.graphics.Rect();
        root.getBoundsInScreen(bounds);
        if (!bounds.contains(x, y)) return null;
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo found = findNodeAtPoint(child, x, y);
                if (found != null) return found;
            }
        }
        return root;
    }

    // Click at a point, trying accessibility action first, then falling back to gesture
    public String smartClick(int x, int y) {
        try {
            AccessibilityNodeInfo root = getRealAppRoot();
            if (root != null) {
                AccessibilityNodeInfo node = findNodeAtPoint(root, x, y);
                if (node != null) {
                    // Try accessibility click first
                    AccessibilityNodeInfo target = node;
                    if (!node.isClickable()) {
                        AccessibilityNodeInfo clickable = findClickableParent(node);
                        if (clickable != null) target = clickable;
                    }
                    if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        return "{\"status\":\"clicked\",\"x\":" + x + ",\"y\":" + y + ",\"method\":\"accessibility\"}";
                    }
                }
            }
            // Fallback to gesture tap
            return clickAtPoint(x, y);
        } catch (Exception e) {
            return "{\"error\":\"smart click failed: " + escapeJson(e.getMessage()) + "\"}";
        }
    }

    // Long press at a point, trying accessibility action first, then falling back to gesture
    public String smartLongPress(int x, int y) {
        try {
            AccessibilityNodeInfo root = getRealAppRoot();
            if (root != null) {
                AccessibilityNodeInfo node = findNodeAtPoint(root, x, y);
                if (node != null) {
                    AccessibilityNodeInfo target = node;
                    if (!node.isClickable() && !node.isLongClickable()) {
                        AccessibilityNodeInfo clickable = findClickableParent(node);
                        if (clickable != null) target = clickable;
                    }
                    if (target.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
                        return "{\"status\":\"long_pressed\",\"x\":" + x + ",\"y\":" + y + ",\"method\":\"accessibility\"}";
                    }
                }
            }
            return longPressAtPoint(x, y);
        } catch (Exception e) {
            return "{\"error\":\"smart long press failed: " + escapeJson(e.getMessage()) + "\"}";
        }
    }

    // Get a quick list of all clickable/interactive elements with their bounds
    public String getClickableElements() {
        try {
            AccessibilityNodeInfo root = getRealAppRoot();
            if (root == null) {
                return "{\"error\":\"No active window content available\"}";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("{\"app\":\"").append(escapeJson(currentAppLabel != null ? currentAppLabel : "")).append("\"");
            sb.append(",\"elements\":[");

            java.util.List<AccessibilityNodeInfo> visited = new ArrayList<>();
            collectClickable(root, sb, visited, 0);

            // Remove trailing comma if any
            String json = sb.toString();
            if (json.endsWith(",")) json = json.substring(0, json.length() - 1);
            json += "]}";

            return json;
        } catch (Exception e) {
            return "{\"error\":\"Failed to get clickable elements: " + escapeJson(e.getMessage()) + "\"}";
        }
    }

    private void collectClickable(AccessibilityNodeInfo node, StringBuilder sb,
                                   List<AccessibilityNodeInfo> visited, int depth) {
        if (node == null || visited.contains(node)) return;
        visited.add(node);

        boolean isInteractive = node.isClickable() || node.isLongClickable() || node.isEditable();
        boolean hasContent = false;
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        String textStr = "";
        String descStr = "";
        if (text != null && text.length() > 0) {
            textStr = text.toString().trim();
            hasContent = true;
        }
        if (desc != null && desc.length() > 0) {
            descStr = desc.toString().trim();
            hasContent = true;
        }

        if (isInteractive && (hasContent || depth <= 3)) {
            android.graphics.Rect bounds = new android.graphics.Rect();
            node.getBoundsInScreen(bounds);

            String role = inferRole(node);
            String resId = node.getViewIdResourceName();

            // Only include if has some content or is clickable
            if (isClickable(node) || hasContent) {
                if (sb.length() > sb.indexOf("[") + 1 && sb.charAt(sb.length() - 1) != '[') {
                    // Check if we need a comma — we track with a simple flag
                }
                sb.append("{\"text\":\"").append(escapeJson(textStr)).append("\"");
                if (!descStr.isEmpty()) sb.append(",\"desc\":\"").append(escapeJson(descStr)).append("\"");
                if (!role.isEmpty()) sb.append(",\"role\":\"").append(escapeJson(role)).append("\"");
                if (resId != null && !resId.isEmpty()) sb.append(",\"resId\":\"").append(escapeJson(resId)).append("\"");
                sb.append(",\"clickable\":").append(node.isClickable());
                sb.append(",\"longClickable\":").append(node.isLongClickable());
                sb.append(",\"editable\":").append(node.isEditable());
                sb.append(",\"bounds\":{\"x\":").append(bounds.left)
                  .append(",\"y\":").append(bounds.top)
                  .append(",\"w\":").append(bounds.width())
                  .append(",\"h\":").append(bounds.height()).append("}");
                // Add center point for easy tapping
                sb.append(",\"center\":{\"x\":").append(bounds.left + bounds.width() / 2)
                  .append(",\"y\":").append(bounds.top + bounds.height() / 2).append("}");
                sb.append("},");
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectClickable(child, sb, visited, depth + 1);
            }
        }
    }

    private boolean isClickable(AccessibilityNodeInfo node) {
        return node.isClickable() || node.isLongClickable();
    }

    // Get screen size
    public String getScreenSize() {
        try {
            android.view.WindowManager wm = (android.view.WindowManager) getSystemService(android.content.Context.WINDOW_SERVICE);
            android.view.Display display = wm.getDefaultDisplay();
            android.graphics.Point size = new android.graphics.Point();
            display.getSize(size);
            return "{\"width\":" + size.x + ",\"height\":" + size.y + "}";
        } catch (Exception e) {
            return "{\"width\":1080,\"height\":1920}";
        }
    }

    // ── Phone Automation Tools (Play Store Compliant) ──
    // All tools use intent-based approaches that let the user confirm actions,
    // avoiding restricted permissions (SEND_SMS, READ_SMS, READ_CALL_LOG).

    // Dial a phone number — opens dialer with number pre-filled (no CALL_PHONE permission needed)
    public String dialPhoneNumber(String number) {
        try {
            String cleaned = number.replaceAll("[^0-9+*#,]", "");
            if (cleaned.isEmpty()) return "{\"error\":\"No valid phone number\"}";
            Intent intent = new Intent(Intent.ACTION_DIAL);
            intent.setData(Uri.parse("tel:" + cleaned));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            return "{\"status\":\"Dialing: " + escapeJson(cleaned) + "\"}";
        } catch (Exception e) {
            return "{\"error\":\"Failed to dial: " + escapeJson(e.getMessage()) + "\"}";
        }
    }

    // Send SMS via intent — opens SMS app with number and message pre-filled
    // User presses Send button themselves. No SEND_SMS permission required.
    public String sendSmsViaIntent(String number, String message) {
        try {
            String cleaned = number.replaceAll("[^0-9+*#,]", "");
            if (cleaned.isEmpty()) return "{\"error\":\"No valid phone number\"}";
            Intent intent = new Intent(Intent.ACTION_SENDTO);
            intent.setData(Uri.parse("smsto:" + cleaned));
            intent.putExtra("sms_body", message);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            return "{\"status\":\"SMS ready to send to " + escapeJson(cleaned) + ": " + escapeJson(message.substring(0, Math.min(50, message.length()))) + "...\"}";
        } catch (Exception e) {
            return "{\"error\":\"Failed to open SMS: " + escapeJson(e.getMessage()) + "\"}";
        }
    }

    // Search contacts by name or number — requires READ_CONTACTS permission
    public String searchContacts(String query) {
        try {
            android.content.ContentResolver cr = getContentResolver();
            String selection = android.provider.ContactsContract.Contacts.DISPLAY_NAME + " LIKE ? OR " +
                android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER + " LIKE ?";
            String[] args = new String[]{"%" + query + "%", "%" + query + "%"};

            String[] projection = new String[]{
                android.provider.ContactsContract.Contacts._ID,
                android.provider.ContactsContract.Contacts.DISPLAY_NAME,
                android.provider.ContactsContract.Contacts.HAS_PHONE_NUMBER
            };

            java.util.List<String[]> results = new java.util.ArrayList<>();
            try (android.database.Cursor cursor = cr.query(
                android.provider.ContactsContract.Contacts.CONTENT_URI,
                projection, selection, args,
                android.provider.ContactsContract.Contacts.DISPLAY_NAME + " LIMIT 20")) {
                while (cursor != null && cursor.moveToNext()) {
                    String id = cursor.getString(0);
                    String name = cursor.getString(1);
                    String hasPhone = cursor.getString(2);
                    String phone = "";
                    if (hasPhone != null && hasPhone.equals("1")) {
                        try (android.database.Cursor pc = cr.query(
                            android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                            new String[]{android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER},
                            android.provider.ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
                            new String[]{id}, null)) {
                            if (pc != null && pc.moveToFirst()) {
                                phone = pc.getString(0);
                            }
                        }
                    }
                    results.add(new String[]{name, phone});
                }
            }

            if (results.isEmpty()) {
                return "{\"error\":\"No contacts found for: " + escapeJson(query) + "\"}";
            }

            StringBuilder sb = new StringBuilder("{\"contacts\":[");
            for (int i = 0; i < results.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append("{\"name\":\"").append(escapeJson(results.get(i)[0])).append("\"");
                sb.append(",\"phone\":\"").append(escapeJson(results.get(i)[1])).append("\"}");
            }
            sb.append("]}");
            return sb.toString();
        } catch (SecurityException e) {
            return "{\"error\":\"Contacts permission not granted\"}";
        } catch (Exception e) {
            return "{\"error\":\"Failed to search contacts: " + escapeJson(e.getMessage()) + "\"}";
        }
    }

    // Create calendar event via intent — no WRITE_CALENDAR permission needed
    // Opens calendar app with pre-filled event, user saves it themselves
    public String createCalendarEvent(String title, String description, long startMillis, long endMillis) {
        try {
            Intent intent = new Intent(Intent.ACTION_INSERT);
            intent.setData(android.provider.CalendarContract.Events.CONTENT_URI);
            intent.putExtra(android.provider.CalendarContract.Events.TITLE, title);
            if (description != null && !description.isEmpty()) {
                intent.putExtra(android.provider.CalendarContract.Events.DESCRIPTION, description);
            }
            if (startMillis > 0) {
                intent.putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis);
            }
            if (endMillis > 0) {
                intent.putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, endMillis);
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            return "{\"status\":\"Calendar event ready: " + escapeJson(title) + "\"}";
        } catch (Exception e) {
            return "{\"error\":\"Failed to create calendar event: " + escapeJson(e.getMessage()) + "\"}";
        }
    }

    // Read upcoming calendar events — requires READ_CALENDAR permission
    public String readCalendarEvents(int daysAhead) {
        try {
            android.content.ContentResolver cr = getContentResolver();
            long now = System.currentTimeMillis();
            long endTime = now + (long) daysAhead * 24 * 60 * 60 * 1000;

            String[] projection = new String[]{
                android.provider.CalendarContract.Events.TITLE,
                android.provider.CalendarContract.Events.DTSTART,
                android.provider.CalendarContract.Events.DTEND,
                android.provider.CalendarContract.Events.EVENT_LOCATION,
                android.provider.CalendarContract.Events.DESCRIPTION
            };

            String selection = android.provider.CalendarContract.Events.DTSTART + " >= ? AND " +
                android.provider.CalendarContract.Events.DTSTART + " <= ? AND " +
                android.provider.CalendarContract.Events.DELETED + " = 0";
            String[] args = new String[]{String.valueOf(now), String.valueOf(endTime)};

            java.util.List<String[]> events = new java.util.ArrayList<>();
            try (android.database.Cursor cursor = cr.query(
                android.provider.CalendarContract.Events.CONTENT_URI,
                projection, selection, args,
                android.provider.CalendarContract.Events.DTSTART + " ASC LIMIT 20")) {
                while (cursor != null && cursor.moveToNext()) {
                    events.add(new String[]{
                        cursor.getString(0),  // title
                        String.valueOf(cursor.getLong(1)),  // start
                        cursor.getString(3),  // location
                        cursor.getString(4)   // description
                    });
                }
            }

            if (events.isEmpty()) {
                return "{\"events\":[],\"message\":\"No events in next " + daysAhead + " days\"}";
            }

            StringBuilder sb = new StringBuilder("{\"events\":[");
            for (int i = 0; i < events.size(); i++) {
                if (i > 0) sb.append(",");
                String[] e = events.get(i);
                sb.append("{\"title\":\"").append(escapeJson(e[0] != null ? e[0] : "")).append("\"");
                sb.append(",\"start\":").append(e[1]);
                sb.append(",\"location\":\"").append(escapeJson(e[2] != null ? e[2] : "")).append("\"");
                sb.append(",\"description\":\"").append(escapeJson(e[3] != null ? e[3] : "")).append("\"}");
            }
            sb.append("]}");
            return sb.toString();
        } catch (SecurityException e) {
            return "{\"error\":\"Calendar permission not granted\"}";
        } catch (Exception e) {
            return "{\"error\":\"Failed to read calendar: " + escapeJson(e.getMessage()) + "\"}";
        }
    }

    // Set a timer via intent — no permission needed
    public String setTimer(int seconds, String label) {
        try {
            Intent intent = new Intent(android.provider.AlarmClock.ACTION_SET_TIMER)
                .putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, label != null ? label : "Argos Timer")
                .putExtra(android.provider.AlarmClock.EXTRA_LENGTH, seconds)
                .putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, false);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            return "{\"status\":\"Timer set for " + seconds + " seconds: " + escapeJson(label != null ? label : "") + "\"}";
        } catch (Exception e) {
            return "{\"error\":\"Failed to set timer: " + escapeJson(e.getMessage()) + "\"}";
        }
    }

    // Set an alarm via intent — no permission needed
    public String setAlarm(int hour, int minute, String label) {
        try {
            Intent intent = new Intent(android.provider.AlarmClock.ACTION_SET_ALARM)
                .putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, label != null ? label : "Argos Alarm")
                .putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
                .putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
                .putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, false);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            return "{\"status\":\"Alarm set for " + String.format("%02d:%02d", hour, minute) + ": " + escapeJson(label != null ? label : "") + "\"}";
        } catch (Exception e) {
            return "{\"error\":\"Failed to set alarm: " + escapeJson(e.getMessage()) + "\"}";
        }
    }

    // Get battery level and charging status — no permission needed
    public String getBatteryStatus() {
        try {
            android.content.IntentFilter filter = new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            android.content.Intent battery = registerReceiver(null, filter);
            if (battery == null) return "{\"error\":\"Could not read battery\"}";

            int level = battery.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
            int scale = battery.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1);
            int status = battery.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1);
            int percent = (level * 100) / scale;

            boolean isCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                status == android.os.BatteryManager.BATTERY_STATUS_FULL;

            return "{\"level\":" + percent + ",\"charging\":" + isCharging + "}";
        } catch (Exception e) {
            return "{\"error\":\"Failed to get battery: " + escapeJson(e.getMessage()) + "\"}";
        }
    }

    // Toggle flashlight — uses CameraManager, no CAMERA permission needed for torch
    public String toggleFlashlight(boolean on) {
        try {
            android.hardware.camera2.CameraManager camManager = (android.hardware.camera2.CameraManager)
                getSystemService(android.content.Context.CAMERA_SERVICE);
            if (camManager == null) return "{\"error\":\"Camera service unavailable\"}";
            String cameraId = camManager.getCameraIdList()[0];
            camManager.setTorchMode(cameraId, on);
            return "{\"status\":\"Flashlight " + (on ? "on" : "off") + "\"}";
        } catch (Exception e) {
            return "{\"error\":\"Flashlight failed: " + escapeJson(e.getMessage()) + "\"}";
        }
    }

    // Open maps for a location search — no permission needed
    public String openMaps(String query) {
        try {
            String encoded = java.net.URLEncoder.encode(query, "UTF-8");
            Uri gmmIntentUri = Uri.parse("geo:0,0?q=" + encoded);
            Intent mapIntent = new Intent(Intent.ACTION_VIEW, gmmIntentUri);
            mapIntent.setPackage("com.google.android.apps.maps");
            mapIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (mapIntent.resolveActivity(getPackageManager()) != null) {
                startActivity(mapIntent);
                return "{\"status\":\"Maps opened: " + escapeJson(query) + "\"}";
            } else {
                // Fallback: open in browser
                return openUrl("https://maps.google.com/?q=" + encoded);
            }
        } catch (Exception e) {
            return "{\"error\":\"Failed to open maps: " + escapeJson(e.getMessage()) + "\"}";
        }
    }

    // Start navigation to a destination — no permission needed
    public String startNavigation(String destination) {
        try {
            String encoded = java.net.URLEncoder.encode(destination, "UTF-8");
            Uri navUri = Uri.parse("google.navigation:q=" + encoded + "&mode=d");
            Intent navIntent = new Intent(Intent.ACTION_VIEW, navUri);
            navIntent.setPackage("com.google.android.apps.maps");
            navIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (navIntent.resolveActivity(getPackageManager()) != null) {
                startActivity(navIntent);
                return "{\"status\":\"Navigating to: " + escapeJson(destination) + "\"}";
            } else {
                return openUrl("https://www.google.com/maps/dir/?api=1&destination=" + encoded);
            }
        } catch (Exception e) {
            return "{\"error\":\"Failed to start navigation: " + escapeJson(e.getMessage()) + "\"}";
        }
    }

    // Share text content via system share sheet — no permission needed
    public String shareContent(String text) {
        try {
            Intent sendIntent = new Intent();
            sendIntent.setAction(Intent.ACTION_SEND);
            sendIntent.putExtra(Intent.EXTRA_TEXT, text);
            sendIntent.setType("text/plain");
            Intent shareIntent = Intent.createChooser(sendIntent, "Share via");
            shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(shareIntent);
            return "{\"status\":\"Share sheet opened\"}";
        } catch (Exception e) {
            return "{\"error\":\"Failed to share: " + escapeJson(e.getMessage()) + "\"}";
        }
    }

    // Play music — opens default music app with search query
    public String playMusic(String query) {
        try {
            // Try to open YouTube Music or default music app
            String encoded = java.net.URLEncoder.encode(query, "UTF-8");
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse("https://music.youtube.com/search?q=" + encoded));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            return "{\"status\":\"Playing music: " + escapeJson(query) + "\"}";
        } catch (Exception e) {
            return "{\"error\":\"Failed to play music: " + escapeJson(e.getMessage()) + "\"}";
        }
    }

    // Open a specific settings page — no permission needed
    public String openSettings(String settingType) {
        try {
            String action;
            switch (settingType.toLowerCase()) {
                case "wifi": action = android.provider.Settings.ACTION_WIFI_SETTINGS; break;
                case "bluetooth": action = android.provider.Settings.ACTION_BLUETOOTH_SETTINGS; break;
                case "location": action = android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS; break;
                case "display": action = android.provider.Settings.ACTION_DISPLAY_SETTINGS; break;
                case "sound": action = android.provider.Settings.ACTION_SOUND_SETTINGS; break;
                case "battery": action = android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS; break;
                case "apps": action = android.provider.Settings.ACTION_APPLICATION_SETTINGS; break;
                case "notification": action = "android.settings.NOTIFICATION_SETTINGS"; break;
                case "airplane": action = android.provider.Settings.ACTION_AIRPLANE_MODE_SETTINGS; break;
                case "data": action = android.provider.Settings.ACTION_DATA_USAGE_SETTINGS; break;
                case "storage": action = android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS; break;
                case "privacy": action = android.provider.Settings.ACTION_PRIVACY_SETTINGS; break;
                case "security": action = android.provider.Settings.ACTION_SECURITY_SETTINGS; break;
                case "accessibility": action = android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS; break;
                default: action = android.provider.Settings.ACTION_SETTINGS;
            }
            Intent intent = new Intent(action);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            return "{\"status\":\"Opened settings: " + escapeJson(settingType) + "\"}";
        } catch (Exception e) {
            return "{\"error\":\"Failed to open settings: " + escapeJson(e.getMessage()) + "\"}";
        }
    }

    // Read clipboard content — no permission needed for foreground access
    public String readClipboard() {
        try {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (clipboard == null || !clipboard.hasPrimaryClip()) {
                return "{\"text\":\"\",\"message\":\"Clipboard is empty\"}";
            }
            android.content.ClipData.Item item = clipboard.getPrimaryClip().getItemAt(0);
            CharSequence text = item.getText();
            if (text != null) {
                return "{\"text\":\"" + escapeJson(text.toString()) + "\"}";
            }
            return "{\"text\":\"\",\"message\":\"Clipboard contains non-text content\"}";
        } catch (Exception e) {
            return "{\"error\":\"Failed to read clipboard: " + escapeJson(e.getMessage()) + "\"}";
        }
    }

    // Get current location — requires ACCESS_FINE_LOCATION permission
    public String getLocation() {
        try {
            android.location.LocationManager lm = (android.location.LocationManager)
                getSystemService(android.content.Context.LOCATION_SERVICE);
            if (lm == null) return "{\"error\":\"Location service unavailable\"}";

            List<String> providers = lm.getProviders(true);
            android.location.Location bestLoc = null;
            for (String provider : providers) {
                try {
                    android.location.Location loc = lm.getLastKnownLocation(provider);
                    if (loc != null && (bestLoc == null || loc.getAccuracy() < bestLoc.getAccuracy())) {
                        bestLoc = loc;
                    }
                } catch (SecurityException ignored) {}
            }

            if (bestLoc == null) {
                return "{\"error\":\"No location available. Enable GPS and try again.\"}";
            }

            return "{\"lat\":" + bestLoc.getLatitude() +
                ",\"lng\":" + bestLoc.getLongitude() +
                ",\"accuracy\":" + bestLoc.getAccuracy() + "}";
        } catch (SecurityException e) {
            return "{\"error\":\"Location permission not granted\"}";
        } catch (Exception e) {
            return "{\"error\":\"Failed to get location: " + escapeJson(e.getMessage()) + "\"}";
        }
    }

    // Go back — global action back
    public String goBack() {
        try {
            boolean success = performGlobalAction(GLOBAL_ACTION_BACK);
            return "{\"status\":\"" + (success ? "went back" : "back failed") + "\"}";
        } catch (Exception e) {
            return "{\"error\":\"Failed to go back: " + escapeJson(e.getMessage()) + "\"}";
        }
    }

    // Go home — global action home
    public String goHome() {
        try {
            boolean success = performGlobalAction(GLOBAL_ACTION_HOME);
            return "{\"status\":\"" + (success ? "went home" : "home failed") + "\"}";
        } catch (Exception e) {
            return "{\"error\":\"Failed to go home: " + escapeJson(e.getMessage()) + "\"}";
        }
    }

    // Open recents — global action recents
    public String openRecents() {
        try {
            boolean success = performGlobalAction(GLOBAL_ACTION_RECENTS);
            return "{\"status\":\"" + (success ? "opened recents" : "recents failed") + "\"}";
        } catch (Exception e) {
            return "{\"error\":\"Failed to open recents: " + escapeJson(e.getMessage()) + "\"}";
        }
    }
}
