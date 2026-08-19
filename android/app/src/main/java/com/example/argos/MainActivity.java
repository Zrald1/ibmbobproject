package com.example.argos;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.graphics.Color;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.pm.PackageManager;
import android.view.accessibility.AccessibilityManager;
import java.util.List;

public class MainActivity extends Activity {

    private static final int OVERLAY_PERMISSION_REQUEST_CODE = 1001;
    private static final int MIC_PERMISSION_REQUEST_CODE = 1002;
    private boolean securityCheckComplete = false;
    private boolean serviceLaunchInProgress = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        System.setProperty("http.agent", "Argos-Android/3.29.5");

        showStartupScreen();

        new Thread(() -> {
            SecurityChecker.SecurityResult secResult = SecurityChecker.check(this);
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (!secResult.isSecure()) {
                    showSecurityWarning(secResult);
                    return;
                }
                securityCheckComplete = true;
                continueStartup();
            });
        }, "argos-security-check").start();
    }

    private void continueStartup() {
        SharedPreferences prefs = getSharedPreferences("argos", MODE_PRIVATE);

        if (!prefs.getBoolean("welcome_shown", false)) {
            showWelcomeScreen();
            return;
        }

        proceedToSetup();
    }

    private void showStartupScreen() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(48, 48, 48, 48);

        ProgressBar progress = new ProgressBar(this);
        progress.setContentDescription("Checking device security");
        layout.addView(progress, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView message = new TextView(this);
        message.setText("Starting Argos…\nChecking device security");
        message.setTextSize(16f);
        message.setGravity(Gravity.CENTER);
        message.setPadding(0, 24, 0, 0);
        message.setContentDescription("Starting Argos. Checking device security.");
        layout.addView(message, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(layout);
    }

    private void showWelcomeScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(56, 80, 56, 56);

        TextView logo = new TextView(this);
        logo.setText("A");
        logo.setTextColor(Color.WHITE);
        logo.setTextSize(48f);
        logo.setTypeface(android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL));
        logo.setGravity(Gravity.CENTER);
        logo.setBackgroundColor(Color.BLACK);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(96, 96);
        logoParams.gravity = Gravity.CENTER;
        logoParams.bottomMargin = 24;
        root.addView(logo, logoParams);

        TextView title = new TextView(this);
        title.setText("Welcome to Argos");
        title.setTextColor(Color.BLACK);
        title.setTextSize(28f);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, 8);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Your AI Companion Robot");
        subtitle.setTextColor(Color.rgb(160, 160, 160));
        subtitle.setTextSize(15f);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, 0, 0, 32);
        root.addView(subtitle);

        TextView whatTitle = new TextView(this);
        whatTitle.setText("What is Argos?");
        whatTitle.setTextColor(Color.BLACK);
        whatTitle.setTextSize(16f);
        whatTitle.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        whatTitle.setPadding(0, 0, 0, 8);
        root.addView(whatTitle);

        TextView whatText = new TextView(this);
        whatText.setText("Argos is a 3D animated AI robot that lives on your screen. It can see what's on your screen, talk with you using voice, and help you navigate your phone.");
        whatText.setTextColor(Color.rgb(80, 80, 80));
        whatText.setTextSize(14f);
        whatText.setLineSpacing(4, 1);
        whatText.setPadding(0, 0, 0, 24);
        root.addView(whatText);

        TextView setupTitle = new TextView(this);
        setupTitle.setText("Setup takes 2 minutes:");
        setupTitle.setTextColor(Color.BLACK);
        setupTitle.setTextSize(16f);
        setupTitle.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        setupTitle.setPadding(0, 0, 0, 12);
        root.addView(setupTitle);

        String[] steps = {
            "Allow Argos to display over other apps",
            "Enable Argos accessibility service",
            "Start chatting with your AI companion!"
        };
        for (int i = 0; i < steps.length; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, 10, 0, 10);

            TextView num = new TextView(this);
            num.setText(String.valueOf(i + 1));
            num.setTextColor(Color.WHITE);
            num.setTextSize(14f);
            num.setGravity(Gravity.CENTER);
            num.setBackgroundColor(Color.BLACK);
            LinearLayout.LayoutParams numParams = new LinearLayout.LayoutParams(32, 32);
            numParams.rightMargin = 16;
            row.addView(num, numParams);

            TextView stepText = new TextView(this);
            stepText.setText(steps[i]);
            stepText.setTextColor(Color.BLACK);
            stepText.setTextSize(14f);
            row.addView(stepText);

            root.addView(row);
        }

        TextView privacyNote = new TextView(this);
        privacyNote.setText("\nYour privacy is protected. Your conversations are never stored, trained on, or shared.");
        privacyNote.setTextColor(Color.rgb(140, 140, 140));
        privacyNote.setTextSize(12f);
        privacyNote.setLineSpacing(3, 1);
        privacyNote.setPadding(0, 16, 0, 32);
        root.addView(privacyNote);

        Button btn = new Button(this);
        btn.setText("Get Started");
        btn.setTextColor(Color.WHITE);
        btn.setBackgroundColor(Color.BLACK);
        btn.setPadding(0, 28, 0, 28);
        btn.setTextSize(16f);
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        root.addView(btn, btnParams);

        btn.setOnClickListener(v -> {
            getSharedPreferences("argos", MODE_PRIVATE).edit().putBoolean("welcome_shown", true).apply();
            proceedToSetup();
        });

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    private void showErrorScreen(String message) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 80, 40, 60);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("Argos");
        title.setTextSize(28f);
        title.setGravity(Gravity.CENTER);
        title.setTextColor(Color.BLACK);
        title.setPadding(0, 0, 0, 24);
        layout.addView(title);

        TextView msg = new TextView(this);
        msg.setText(message);
        msg.setTextSize(14f);
        msg.setTextColor(Color.rgb(200, 0, 0));
        msg.setGravity(Gravity.CENTER);
        layout.addView(msg);

        Button retryBtn = new Button(this);
        retryBtn.setText("Retry");
        retryBtn.setTextColor(Color.WHITE);
        retryBtn.setBackgroundColor(Color.BLACK);
        retryBtn.setPadding(0, 28, 0, 28);
        LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        retryParams.topMargin = 32;
        layout.addView(retryBtn, retryParams);

        retryBtn.setOnClickListener(v -> recreate());

        setContentView(layout);
    }

    private void showSecurityWarning(SecurityChecker.SecurityResult result) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 60, 40, 60);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("Security Notice");
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER);
        title.setTextColor(Color.rgb(220, 140, 30));
        title.setPadding(0, 0, 0, 30);
        layout.addView(title);

        TextView msg = new TextView(this);
        msg.setText("Argos detected some risk factors on this device:\n\n"
            + result.getSummary() + "\n\n"
            + "Risk score: " + result.riskScore + "\n\n"
            + "You can still use Argos, but some features may be limited.");
        msg.setTextSize(14);
        msg.setLineSpacing(4, 1);
        layout.addView(msg);

        Button continueBtn = new Button(this);
        continueBtn.setText("Continue to Argos");
        continueBtn.setTextColor(Color.WHITE);
        continueBtn.setBackgroundColor(Color.BLACK);
        continueBtn.setPadding(0, 28, 0, 28);
        LinearLayout.LayoutParams continueParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        continueParams.topMargin = 32;
        layout.addView(continueBtn, continueParams);

        continueBtn.setOnClickListener(v -> {
            securityCheckComplete = true;
            continueStartup();
        });

        Button retryBtn = new Button(this);
        retryBtn.setText("Retry Check");
        retryBtn.setTextColor(Color.rgb(140, 140, 140));
        retryBtn.setBackgroundColor(Color.WHITE);
        retryBtn.setPadding(0, 20, 0, 20);
        LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        retryParams.topMargin = 12;
        layout.addView(retryBtn, retryParams);

        retryBtn.setOnClickListener(v -> recreate());

        setContentView(layout);
    }

    private void proceedToSetup() {
        if (!hasMicPermission()) {
            requestMicPermission();
            return;
        }

        if (hasOverlayPermission()) {
            if (!isAccessibilityEnabled()) {
                promptAccessibility();
            } else if (startFloatingService()) {
                finish();
            }
        } else {
            requestOverlayPermission();
        }
    }

    private boolean hasOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(this);
        }
        return true;
    }

    private boolean hasMicPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void requestMicPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(
                new String[]{android.Manifest.permission.RECORD_AUDIO},
                MIC_PERMISSION_REQUEST_CODE);
        }
    }

    private void requestOverlayPermission() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(56, 60, 56, 56);

        TextView stepBadge = new TextView(this);
        stepBadge.setText("Step 1 of 2");
        stepBadge.setTextColor(Color.rgb(160, 160, 160));
        stepBadge.setTextSize(13f);
        stepBadge.setGravity(Gravity.CENTER);
        stepBadge.setPadding(0, 0, 0, 16);
        root.addView(stepBadge);

        TextView title = new TextView(this);
        title.setText("Allow Argos to float over apps");
        title.setTextColor(Color.BLACK);
        title.setTextSize(26f);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, 20);
        root.addView(title);

        TextView desc = new TextView(this);
        desc.setText("Argos appears as a small robot on top of your screen while you use other apps. This requires the \"Display over other apps\" permission.");
        desc.setTextColor(Color.rgb(100, 100, 100));
        desc.setTextSize(15f);
        desc.setGravity(Gravity.CENTER);
        desc.setPadding(0, 0, 0, 32);
        root.addView(desc);

        String[] steps = {
            "Tap \"Open Settings\" below",
            "Find Argos in the app list",
            "Toggle \"Allow display over other apps\"",
            "Press back to return here",
            "Tap \"I've Enabled It\" below"
        };
        for (int i = 0; i < steps.length; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, 12, 0, 12);

            TextView num = new TextView(this);
            num.setText(String.valueOf(i + 1));
            num.setTextColor(Color.WHITE);
            num.setTextSize(14f);
            num.setGravity(Gravity.CENTER);
            num.setBackgroundColor(Color.BLACK);
            LinearLayout.LayoutParams numParams = new LinearLayout.LayoutParams(32, 32);
            numParams.rightMargin = 16;
            row.addView(num, numParams);

            TextView stepText = new TextView(this);
            stepText.setText(steps[i]);
            stepText.setTextColor(Color.BLACK);
            stepText.setTextSize(15f);
            row.addView(stepText);

            root.addView(row);
        }

        View spacer = new View(this);
        LinearLayout.LayoutParams spacerParams = new LinearLayout.LayoutParams(0, 32);
        root.addView(spacer, spacerParams);

        Button btn = new Button(this);
        btn.setText("Open Settings");
        btn.setTextColor(Color.WHITE);
        btn.setBackgroundColor(Color.BLACK);
        btn.setPadding(0, 28, 0, 28);
        btn.setTextSize(16f);
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnParams.bottomMargin = 12;
        root.addView(btn, btnParams);

        btn.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST_CODE);
            }
        });

        Button continueBtn = new Button(this);
        continueBtn.setText("I've Enabled It — Continue");
        continueBtn.setTextColor(Color.BLACK);
        continueBtn.setBackgroundColor(Color.rgb(230, 230, 230));
        continueBtn.setPadding(0, 24, 0, 24);
        continueBtn.setTextSize(15f);
        LinearLayout.LayoutParams continueParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        continueParams.bottomMargin = 12;
        root.addView(continueBtn, continueParams);

        continueBtn.setOnClickListener(v -> {
            if (hasOverlayPermission()) {
                if (!isAccessibilityEnabled()) {
                    promptAccessibility();
                } else if (startFloatingService()) {
                    finish();
                }
            } else {
                Toast.makeText(this, "Overlay permission not granted yet. Please enable it in Settings.", Toast.LENGTH_LONG).show();
            }
        });

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == OVERLAY_PERMISSION_REQUEST_CODE) {
            if (hasOverlayPermission()) {
                if (!isAccessibilityEnabled()) {
                    promptAccessibility();
                } else if (startFloatingService()) {
                    finish();
                }
            } else {
                requestOverlayPermission();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == MIC_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                proceedToSetup();
            } else {
                Toast.makeText(this, "Microphone permission is needed for voice input. Please grant it to use tap-to-speak.", Toast.LENGTH_LONG).show();
                if (hasOverlayPermission()) {
                    if (!isAccessibilityEnabled()) {
                        promptAccessibility();
                    } else if (startFloatingService()) {
                        finish();
                    }
                } else {
                    requestOverlayPermission();
                }
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (!securityCheckComplete || isFinishing() || serviceLaunchInProgress) return;

        String permRequest = getIntent().getStringExtra("request_permission");
        if (permRequest != null && permRequest.equals(android.Manifest.permission.CAMERA)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                requestPermissions(new String[]{android.Manifest.permission.CAMERA}, 200);
            }
            getIntent().removeExtra("request_permission");
        }

        if (hasOverlayPermission() && isAccessibilityEnabled()) {
            if (!hasMicPermission()) {
                requestMicPermission();
                return;
            }
            if (startFloatingService()) finish();
        }
    }

    private boolean startFloatingService() {
        if (!hasOverlayPermission()) {
            requestOverlayPermission();
            return false;
        }
        if (serviceLaunchInProgress) return true;

        Intent intent = new Intent(this, FloatingRobotService.class);
        serviceLaunchInProgress = true;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            return true;
        } catch (RuntimeException e) {
            serviceLaunchInProgress = false;
            showErrorScreen("Argos could not start its foreground service. "
                + "Check notifications and Display over other apps in Settings, then retry.");
            return false;
        }
    }

    private boolean isAccessibilityEnabled() {
        AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        if (am == null) return false;
        List<AccessibilityServiceInfo> enabled = am.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        if (enabled == null) return false;
        for (AccessibilityServiceInfo info : enabled) {
            String id = info.getId();
            if (id != null && id.startsWith(getPackageName() + "/")) return true;
        }
        return false;
    }

    private void promptAccessibility() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(56, 80, 56, 56);

        TextView disclosureTitle = new TextView(this);
        disclosureTitle.setText("Data Access Disclosure");
        disclosureTitle.setTextColor(Color.BLACK);
        disclosureTitle.setTextSize(16f);
        disclosureTitle.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        disclosureTitle.setGravity(Gravity.CENTER);
        disclosureTitle.setPadding(0, 0, 0, 12);
        root.addView(disclosureTitle);

        TextView disclosure = new TextView(this);
        disclosure.setText("Argos uses the AccessibilityService API to:\n\n"
            + "• Read text and UI elements visible on your screen\n"
            + "• Identify which app you are currently using\n"
            + "• Perform clicks, typing, scrolling, and gestures on your behalf\n"
            + "• Read and reply to notifications\n"
            + "• Take screenshots for OCR (text recognition)\n\n"
            + "This data is sent to our AI server to generate helpful responses and actions. "
            + "Screen content is processed in real-time and is not permanently stored. "
            + "Sensitive data (passwords, credit card numbers) is automatically filtered out.\n\n"
            + "You can disable the accessibility service at any time in Settings > Accessibility.");
        disclosure.setTextColor(Color.rgb(80, 80, 80));
        disclosure.setTextSize(14f);
        disclosure.setGravity(Gravity.START);
        disclosure.setPadding(0, 0, 0, 24);
        root.addView(disclosure);

        final boolean[] consented = {false};
        LinearLayout consentRow = new LinearLayout(this);
        consentRow.setOrientation(LinearLayout.HORIZONTAL);
        consentRow.setGravity(Gravity.CENTER_VERTICAL);
        consentRow.setPadding(0, 0, 0, 24);

        final View consentBox = new View(this) {
            @Override
            protected void onDraw(android.graphics.Canvas canvas) {
                float w = getWidth();
                float h = getHeight();
                android.graphics.RectF rect = new android.graphics.RectF(0, 0, w, h);

                android.graphics.Paint fillPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                fillPaint.setColor(Color.WHITE);
                fillPaint.setStyle(android.graphics.Paint.Style.FILL);
                canvas.drawRoundRect(rect, 8f, 8f, fillPaint);

                android.graphics.Paint borderPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                borderPaint.setColor(Color.BLACK);
                borderPaint.setStyle(android.graphics.Paint.Style.STROKE);
                borderPaint.setStrokeWidth(4f);
                canvas.drawRoundRect(rect, 8f, 8f, borderPaint);

                if (consented[0]) {
                    android.graphics.Paint checkPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                    checkPaint.setColor(Color.BLACK);
                    checkPaint.setStyle(android.graphics.Paint.Style.STROKE);
                    checkPaint.setStrokeWidth(6f);
                    checkPaint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
                    checkPaint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
                    android.graphics.Path path = new android.graphics.Path();
                    path.moveTo(w * 0.22f, h * 0.50f);
                    path.lineTo(w * 0.45f, h * 0.72f);
                    path.lineTo(w * 0.78f, h * 0.28f);
                    canvas.drawPath(path, checkPaint);
                }
            }
        };
        consentBox.setClickable(true);
        consentBox.setOnClickListener(v -> {
            consented[0] = !consented[0];
            consentBox.invalidate();
        });
        int boxSize = (int)(44 * getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams boxParams = new LinearLayout.LayoutParams(boxSize, boxSize);
        boxParams.rightMargin = 16;
        consentRow.addView(consentBox, boxParams);

        TextView consentText = new TextView(this);
        consentText.setText("I understand and consent to Argos accessing my screen content");
        consentText.setTextColor(Color.BLACK);
        consentText.setTextSize(14f);
        consentRow.addView(consentText);

        root.addView(consentRow);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            TextView restrictedTitle = new TextView(this);
            restrictedTitle.setText("For Android 13+ users");
            restrictedTitle.setTextColor(Color.BLACK);
            restrictedTitle.setTextSize(16f);
            restrictedTitle.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            restrictedTitle.setGravity(Gravity.CENTER);
            restrictedTitle.setPadding(0, 0, 0, 12);
            root.addView(restrictedTitle);

            String[] restrictedSteps = {
                "Tap \"Open App Info\" below",
                "Tap the three dots menu (top right)",
                "Select \"Allow restricted settings\"",
                "Come back and continue to Step 2"
            };
            for (int i = 0; i < restrictedSteps.length; i++) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, 10, 0, 10);

                TextView num = new TextView(this);
                num.setText(String.valueOf(i + 1));
                num.setTextColor(Color.WHITE);
                num.setTextSize(13f);
                num.setGravity(Gravity.CENTER);
                num.setBackgroundColor(Color.BLACK);
                LinearLayout.LayoutParams numParams = new LinearLayout.LayoutParams(28, 28);
                numParams.rightMargin = 14;
                row.addView(num, numParams);

                TextView stepText = new TextView(this);
                stepText.setText(restrictedSteps[i]);
                stepText.setTextColor(Color.BLACK);
                stepText.setTextSize(14f);
                row.addView(stepText);

                root.addView(row);
            }

            Button restrictedBtn = new Button(this);
            restrictedBtn.setText("Open App Info");
            restrictedBtn.setTextColor(Color.BLACK);
            restrictedBtn.setBackgroundColor(Color.WHITE);
            restrictedBtn.setPadding(0, 24, 0, 24);
            restrictedBtn.setTextSize(15f);
            LinearLayout.LayoutParams restrictedParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            restrictedParams.topMargin = 16;
            restrictedParams.bottomMargin = 32;
            root.addView(restrictedBtn, restrictedParams);

            restrictedBtn.setOnClickListener(v -> {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            });
        }

        TextView step2Title = new TextView(this);
        step2Title.setText(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ? "Enable Accessibility Service" : "Steps");
        step2Title.setTextColor(Color.BLACK);
        step2Title.setTextSize(16f);
        step2Title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        step2Title.setGravity(Gravity.CENTER);
        step2Title.setPadding(0, 0, 0, 12);
        root.addView(step2Title);

        String[] accessSteps = {
            "Tap \"Open Accessibility Settings\"",
            "Find Argos in the service list",
            "Toggle it on and confirm",
            "Press back to return here"
        };
        for (int i = 0; i < accessSteps.length; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, 10, 0, 10);

            TextView num = new TextView(this);
            num.setText(String.valueOf(i + 1));
            num.setTextColor(Color.WHITE);
            num.setTextSize(13f);
            num.setGravity(Gravity.CENTER);
            num.setBackgroundColor(Color.BLACK);
            LinearLayout.LayoutParams numParams = new LinearLayout.LayoutParams(28, 28);
            numParams.rightMargin = 14;
            row.addView(num, numParams);

            TextView stepText = new TextView(this);
            stepText.setText(accessSteps[i]);
            stepText.setTextColor(Color.BLACK);
            stepText.setTextSize(14f);
            row.addView(stepText);

            root.addView(row);
        }

        View spacer = new View(this);
        LinearLayout.LayoutParams spacerParams = new LinearLayout.LayoutParams(0, 24);
        root.addView(spacer, spacerParams);

        Button enableBtn = new Button(this);
        enableBtn.setText("Open Accessibility Settings");
        enableBtn.setTextColor(Color.WHITE);
        enableBtn.setBackgroundColor(Color.BLACK);
        enableBtn.setPadding(0, 28, 0, 28);
        enableBtn.setTextSize(16f);
        LinearLayout.LayoutParams enableParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        enableParams.bottomMargin = 12;
        root.addView(enableBtn, enableParams);

        enableBtn.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        });

        Button continueAccessBtn = new Button(this);
        continueAccessBtn.setText("I've Enabled It — Continue");
        continueAccessBtn.setTextColor(Color.BLACK);
        continueAccessBtn.setBackgroundColor(Color.rgb(230, 230, 230));
        continueAccessBtn.setPadding(0, 24, 0, 24);
        continueAccessBtn.setTextSize(15f);
        LinearLayout.LayoutParams continueAccessParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        continueAccessParams.bottomMargin = 12;
        root.addView(continueAccessBtn, continueAccessParams);

        continueAccessBtn.setOnClickListener(v -> {
            if (!consented[0]) {
                Toast.makeText(this, "Please check the consent box first to confirm you understand Argos will access your screen.", Toast.LENGTH_LONG).show();
                return;
            }
            if (isAccessibilityEnabled()) {
                if (startFloatingService()) finish();
            } else {
                Toast.makeText(this, "Accessibility service not enabled yet. Please find Argos in the list and toggle it on.", Toast.LENGTH_LONG).show();
            }
        });

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }
}
