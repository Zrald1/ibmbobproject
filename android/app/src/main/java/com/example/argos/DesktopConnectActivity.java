package com.example.argos;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mlkit.vision.codescanner.GmsBarcodeScanner;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;

import org.json.JSONObject;

/**
 * Pair + control screen for the Argos desktop link.
 *
 * "Scan QR" reads the code the desktop shows (Phone tab). The payload either
 * points at the backend relay (v=2) or at the desktop directly on the LAN
 * (v=1) — DesktopLink handles both.
 */
public class DesktopConnectActivity extends Activity {

    private DesktopLink link;
    private TextView statusText;
    private TextView resultText;
    private EditText taskInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        link = new DesktopLink(this);
        buildUi();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 48, 48, 48);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("Argos Desktop Link");
        title.setTextColor(Color.BLACK);
        title.setTextSize(20f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        statusText = new TextView(this);
        statusText.setTextSize(14f);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(0, 16, 0, 16);
        root.addView(statusText);
        refreshStatus();

        if (link.isPaired()) {
            buildPairedControls(root);
        } else {
            buildScanControls(root);
        }

        resultText = new TextView(this);
        resultText.setTextColor(Color.rgb(60, 60, 60));
        resultText.setTextSize(12f);
        resultText.setPadding(0, 16, 0, 0);
        resultText.setTextIsSelectable(true);
        root.addView(resultText);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    private void refreshStatus() {
        if (link.isPaired()) {
            statusText.setText("● Connected to " + link.desktopName());
            statusText.setTextColor(Color.rgb(0, 140, 70));
        } else {
            statusText.setText("○ Not paired — scan the QR code shown in the desktop app's Phone tab");
            statusText.setTextColor(Color.rgb(180, 90, 40));
        }
    }

    private void buildScanControls(LinearLayout root) {
        Button scan = new Button(this);
        scan.setText("Scan Desktop QR Code");
        scan.setTextColor(Color.WHITE);
        scan.setBackgroundColor(Color.BLACK);
        scan.setPadding(0, 28, 0, 28);
        scan.setTextSize(16f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 24;
        root.addView(scan, lp);

        scan.setOnClickListener(v -> startScan());
    }

    private void buildPairedControls(LinearLayout root) {
        taskInput = new EditText(this);
        taskInput.setHint("Task for the desktop… (e.g. open notepad)");
        taskInput.setTextSize(14f);
        taskInput.setPadding(20, 20, 20, 20);
        LinearLayout.LayoutParams inLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inLp.bottomMargin = 14;
        root.addView(taskInput, inLp);

        Button send = button("Send Task");
        send.setOnClickListener(v -> sendTask());
        root.addView(send, buttonLp());

        Button ping = button("Ping Desktop");
        ping.setOnClickListener(v -> quickCommand("phone.ping", new JSONObject()));
        root.addView(ping, buttonLp());

        Button wave = button("Robot: Happy");
        wave.setOnClickListener(v -> {
            try {
                quickCommand("robot.expression", new JSONObject().put("name", "happy"));
            } catch (Exception ignored) {}
        });
        root.addView(wave, buttonLp());

        Button status = button("Desktop Status");
        status.setOnClickListener(v -> quickCommand("desktop.status", new JSONObject()));
        root.addView(status, buttonLp());

        Button tools = button("List Desktop Tools");
        tools.setOnClickListener(v -> quickCommand("tools.list", new JSONObject()));
        root.addView(tools, buttonLp());

        Button unpair = new Button(this);
        unpair.setText("Unpair");
        unpair.setTextColor(Color.BLACK);
        unpair.setBackgroundColor(Color.rgb(230, 230, 230));
        unpair.setPadding(0, 22, 0, 22);
        unpair.setTextSize(14f);
        LinearLayout.LayoutParams up = buttonLp();
        up.topMargin = 20;
        root.addView(unpair, up);
        unpair.setOnClickListener(v -> {
            link.unpair();
            buildUi();
        });
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.BLACK);
        b.setPadding(0, 24, 0, 24);
        b.setTextSize(15f);
        return b;
    }

    private LinearLayout.LayoutParams buttonLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = 12;
        return lp;
    }

    private void startScan() {
        GmsBarcodeScannerOptions opts = new GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE)
                .enableAutoZoom()
                .build();
        GmsBarcodeScanner scanner = GmsBarcodeScanning.getClient(this, opts);
        scanner.startScan()
                .addOnSuccessListener(barcode -> {
                    String raw = barcode.getRawValue();
                    if (raw == null) return;
                    resultText.setText("QR scanned — pairing…");
                    link.pair(raw, (resp, err) -> {
                        if (err != null) {
                            resultText.setText("Pairing failed: " + err.getMessage());
                        } else {
                            Toast.makeText(this, "Paired with " + link.desktopName(),
                                    Toast.LENGTH_LONG).show();
                            buildUi();
                        }
                    });
                })
                .addOnCanceledListener(() -> {})
                .addOnFailureListener(e ->
                        resultText.setText("Scanner failed: " + e.getMessage()));
    }

    private void sendTask() {
        String text = taskInput.getText().toString().trim();
        if (text.isEmpty()) return;
        try {
            sendCommand("task.prompt", new JSONObject().put("text", text));
        } catch (Exception ignored) {}
    }

    private void quickCommand(String method, JSONObject params) {
        sendCommand(method, params);
    }

    private void sendCommand(String method, JSONObject params) {
        resultText.setText("Sending " + method + "…");
        link.sendCommand(method, params, (resp, err) -> {
            if (err != null) {
                resultText.setText("Failed: " + err.getMessage());
                return;
            }
            // LAN mode returns the result inline; backend mode queues + polls.
            String state = resp.optString("state", "");
            if (resp.has("command_id") && !"done".equals(state)) {
                String cid = resp.optString("command_id");
                resultText.setText("Queued on " + link.desktopName() + " (" + cid + ") — waiting…");
                link.pollResult(cid, 20, (s, e2) -> {
                    if (e2 != null) resultText.setText("Result error: " + e2.getMessage());
                    else resultText.setText(pretty(s));
                });
            } else {
                resultText.setText(pretty(resp));
            }
        });
    }

    private static String pretty(JSONObject j) {
        try {
            return j.toString(2);
        } catch (Exception e) {
            return j.toString();
        }
    }
}
