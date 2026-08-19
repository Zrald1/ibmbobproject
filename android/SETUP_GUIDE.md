# Argos Android setup and recovery guide

This guide is for a phone that is not rooted but cannot reach the Argos setup screen, or needs help enabling the permissions required by the floating assistant.

## What was fixed

Argos no longer performs the device security scan on the main UI thread. A startup screen is shown while the scan runs, so a slow package manager or OEM filesystem does not make the app look like it failed to open.

The local checks were also changed to avoid treating these conditions as proof of root:

- A legitimate app whose package name contains a generic word such as `patcher` or `cracker`.
- Android test keys, an unlocked bootloader, a custom OS, or an emulator.
- A debug build used for development.
- A slow or interactive `su`, `which`, `getenforce`, or `ps` shell command.

Strong signals such as a detected root binary, Magisk/KernelSU/APatch files, a production APK that is modified, or a hook framework can still stop the service. The backend should use Google Play Integrity for sensitive actions rather than relying only on local heuristics.

## First launch

1. Install the complete APK and open **Argos** from the launcher.
2. Keep the phone online for the first device authentication.
3. If the app shows **Allow Argos to float over apps**, tap **Open Settings**.
4. Select **Argos** and enable **Allow display over other apps** (the wording varies by phone manufacturer).
5. Press Back until Argos is visible again.
6. Argos then shows the accessibility disclosure. Read it and tick the consent box only if you want the assistant to read and interact with visible app content.
7. Tap **Open Accessibility Settings**.
8. Select **Argos screen assistant**, turn it on, and confirm the Android warning.
9. Press Back to Argos. The floating robot starts after both the overlay and accessibility permissions are enabled.

Accessibility is optional for basic account access. Use **Skip — Basic Chat Only** if the overlay permission is already enabled but you do not want Argos to read or control other apps.

## Android 13 and newer: Allow restricted settings

Android can restrict accessibility services installed outside Google Play. If the Accessibility screen does not show an enable switch, or Android says the setting is restricted:

1. Open **Settings > Apps > Argos** (also called **App info**).
2. Tap the three-dot menu in the upper-right corner.
3. Choose **Allow restricted settings** and confirm.
4. Return to **Settings > Accessibility**.
5. Open **Argos screen assistant**, enable it, and confirm.
6. Return to Argos.

Only allow this for an APK obtained from a source you trust. If the three-dot option is missing, uninstall the APK and install the signed release supplied by the project or app store, then repeat the steps.

## If Argos still does not open

1. Restart the phone and try again.
2. Open **Settings > Apps > Argos > Permissions** and allow only permissions needed for the feature you use.
3. Confirm **Display over other apps** is enabled under **Settings > Apps > Special app access**.
4. Confirm the Accessibility service is enabled under **Settings > Accessibility > Installed apps**.
5. On Android 13+, allow notifications if you want the persistent foreground-service status notification: **Settings > Apps > Argos > Notifications**.
6. On Samsung, Xiaomi, Oppo, Vivo, Huawei, and similar devices, allow Argos to run in the background or disable battery optimization for Argos. The exact menu name is manufacturer-specific.
7. If the app shows a security warning, use the exact reason shown on screen. Do not root the phone or install a bypass tool. Remove a real root or hook framework, restart, and tap **Retry**.
8. If the app closes immediately, capture the Android crash reason and share it with the developer:

   ```text
   adb logcat -c
   adb logcat -v time AndroidRuntime:E ArgosSecurity:E ArgosService:E ArgosNative:E *:S
   ```

   Reproduce the launch problem while that command is running. Do not include authentication tokens or personal screen content in a bug report.

## Privacy and safety

The accessibility service can read visible UI text and perform actions such as clicks, typing, scrolling, and gestures. Do not enable it unless you understand and accept that access. Argos provides privacy mode and per-app blocking; use those controls for banking, password-manager, health, and other sensitive apps. Turn the service off from **Settings > Accessibility** whenever it is not needed.

The overlay permission lets Argos draw above other apps. Disable it from **Settings > Apps > Special app access > Display over other apps** to stop the floating robot.

Argos stores authentication data in encrypted preferences when Android Keystore is available, uses HTTPS-only networking, and keeps the accessibility service protected by `BIND_ACCESSIBILITY_SERVICE`. Local root checks are only one defense; production deployments should validate Play Integrity verdicts on the backend before granting sensitive operations.

## Official Android references

- [Create an accessibility service](https://developer.android.com/guide/topics/ui/accessibility/service)
- [AccessibilityService API reference](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)
- [Play Integrity overview](https://developer.android.com/google/play/integrity/overview)
- [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types)
