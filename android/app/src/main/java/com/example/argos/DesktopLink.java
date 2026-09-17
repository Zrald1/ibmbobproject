package com.example.argos;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Link between the phone and an Argos Desktop instance.
 *
 * Two transports, chosen by what the QR code carried:
 *   v=2 (backend relay) — {url, fallback, desktop, pair}. Pair via
 *         POST /api/link/pair, then commands go POST /api/link/command and
 *         results come back via POST /api/link/status.
 *   v=1 (direct LAN)    — {ips[], port, token}. Commands go straight to
 *         http://<ip>:<port>/command with a Bearer token.
 *
 * The pairing credential (pair_code / LAN token) lives only in the QR and
 * in memory — what we persist is the minted phone_token, in
 * EncryptedSharedPreferences when available.
 */
public class DesktopLink {

    public interface Callback {
        void onResult(JSONObject response, Exception error);
    }

    private static final ExecutorService NET = Executors.newCachedThreadPool();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int TIMEOUT_MS = 9000;

    private final Context appContext;

    // Pairing state (persisted)
    private String baseUrl;      // primary backend or "" for LAN mode
    private String fallbackUrl;  // java-backend fallback
    private String desktopId;
    private String phoneToken;   // backend mode credential
    private String desktopName;

    // LAN mode
    private String[] lanIps;
    private int lanPort;
    private String lanToken;
    private String lanWorkingIp; // last IP that answered — tried first

    public DesktopLink(Context ctx) {
        appContext = ctx.getApplicationContext();
        load();
    }

    // ── persistence ────────────────────────────────────────────────────────

    private SharedPreferences prefs() {
        try {
            MasterKey key = new MasterKey.Builder(appContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            return EncryptedSharedPreferences.create(
                    appContext, "argos_desktop", key,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (Exception e) {
            return appContext.getSharedPreferences("argos_desktop", Context.MODE_PRIVATE);
        }
    }

    private void load() {
        SharedPreferences p = prefs();
        baseUrl = p.getString("url", "");
        fallbackUrl = p.getString("fallback", "");
        desktopId = p.getString("desktop", "");
        phoneToken = p.getString("phone_token", "");
        desktopName = p.getString("desktop_name", "");
        lanPort = p.getInt("lan_port", 0);
        lanToken = p.getString("lan_token", "");
        lanWorkingIp = p.getString("lan_working_ip", "");
        String ips = p.getString("lan_ips", "");
        lanIps = ips.isEmpty() ? new String[0] : ips.split(",");
    }

    public boolean isPaired() {
        boolean backend = !baseUrl.isEmpty() && !desktopId.isEmpty() && !phoneToken.isEmpty();
        boolean lan = lanIps.length > 0 && lanPort > 0 && !lanToken.isEmpty();
        return backend || lan;
    }

    public boolean isBackendMode() {
        return !desktopId.isEmpty() && !phoneToken.isEmpty();
    }

    public String desktopName() {
        return desktopName.isEmpty() ? desktopId : desktopName;
    }

    public void unpair() {
        prefs().edit().clear().apply();
        baseUrl = fallbackUrl = desktopId = phoneToken = desktopName = "";
        lanIps = new String[0];
        lanPort = 0;
        lanToken = lanWorkingIp = "";
    }

    // ── pairing ────────────────────────────────────────────────────────────

    /** Called with the raw QR contents after a successful scan. */
    public void pair(String qrPayload, Callback cb) {
        NET.execute(() -> {
            try {
                JSONObject q = new JSONObject(qrPayload);
                if (!"argos".equals(q.optString("app")))
                    throw new Exception("Not an Argos QR code");

                int v = q.optInt("v", 1);
                if (v == 2) {
                    pairBackend(q);
                } else {
                    pairLan(q);
                }
                post(cb, new JSONObject().put("ok", true)
                        .put("desktop", desktopName()), null);
            } catch (Exception e) {
                post(cb, null, e);
            }
        });
    }

    private void pairBackend(JSONObject q) throws Exception {
        String url = q.getString("url");
        String fallback = q.optString("fallback", "");
        String desktop = q.getString("desktop");
        String pair = q.getString("pair");

        JSONObject body = new JSONObject()
                .put("desktop_id", desktop)
                .put("pair_code", pair)
                .put("phone_name", android.os.Build.MODEL);

        JSONObject resp = postFirst(url, fallback, "/api/link/pair", body);
        if (!resp.optBoolean("ok"))
            throw new Exception("pairing rejected: " + resp.optString("detail", "unknown"));

        baseUrl = url;
        fallbackUrl = fallback;
        desktopId = desktop;
        phoneToken = resp.getString("phone_token");
        desktopName = resp.optString("desktop_name", desktop);
        save();
    }

    private void pairLan(JSONObject q) throws Exception {
        JSONArray arr = q.getJSONArray("ips");
        lanIps = new String[arr.length()];
        for (int i = 0; i < arr.length(); i++) lanIps[i] = arr.getString(i);
        lanPort = q.getInt("port");
        lanToken = q.getString("token");
        desktopName = q.optString("name", "Argos PC");

        // Verify at least one address answers before saving the pairing.
        Exception last = null;
        for (String ip : lanIps) {
            try {
                JSONObject ping = lanPost(ip, new JSONObject()
                        .put("method", "phone.ping")
                        .put("params", new JSONObject()));
                if (ping.optBoolean("ok")) {
                    lanWorkingIp = ip;
                    save();
                    return;
                }
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new Exception("no desktop reachable");
    }

    private void save() {
        StringBuilder ips = new StringBuilder();
        for (String ip : lanIps) {
            if (ips.length() > 0) ips.append(',');
            ips.append(ip);
        }
        prefs().edit()
                .putString("url", baseUrl)
                .putString("fallback", fallbackUrl)
                .putString("desktop", desktopId)
                .putString("phone_token", phoneToken)
                .putString("desktop_name", desktopName)
                .putString("lan_ips", ips.toString())
                .putInt("lan_port", lanPort)
                .putString("lan_token", lanToken)
                .putString("lan_working_ip", lanWorkingIp)
                .apply();
    }

    // ── commands ───────────────────────────────────────────────────────────

    /** Queue a command on the desktop. Callback gets the command_id. */
    public void sendCommand(String method, JSONObject params, Callback cb) {
        NET.execute(() -> {
            try {
                JSONObject resp;
                if (isBackendMode()) {
                    resp = postFirst(baseUrl, fallbackUrl, "/api/link/command",
                            new JSONObject()
                                    .put("desktop_id", desktopId)
                                    .put("phone_token", phoneToken)
                                    .put("method", method)
                                    .put("params", params == null ? new JSONObject() : params));
                } else {
                    resp = lanPostBest(new JSONObject()
                            .put("method", method)
                            .put("params", params == null ? new JSONObject() : params));
                }
                post(cb, resp, null);
            } catch (Exception e) {
                post(cb, null, e);
            }
        });
    }

    /** Poll a queued command's result until done/error/expired. */
    public void pollResult(String commandId, int attempts, Callback cb) {
        NET.execute(() -> {
            try {
                for (int i = 0; i < attempts; i++) {
                    JSONObject s;
                    if (isBackendMode()) {
                        s = postFirst(baseUrl, fallbackUrl, "/api/link/status",
                                new JSONObject()
                                        .put("desktop_id", desktopId)
                                        .put("phone_token", phoneToken)
                                        .put("command_id", commandId));
                    } else {
                        // Direct-LAN commands are synchronous — nothing to poll.
                        post(cb, new JSONObject().put("state", "done"), null);
                        return;
                    }
                    String state = s.optString("state", "");
                    if ("done".equals(state) || "error".equals(state)
                            || "expired".equals(state)) {
                        post(cb, s, null);
                        return;
                    }
                    Thread.sleep(900);
                }
                post(cb, null, new Exception("timed out waiting for the desktop"));
            } catch (Exception e) {
                post(cb, null, e);
            }
        });
    }

    // ── HTTP plumbing ──────────────────────────────────────────────────────

    private JSONObject postFirst(String primary, String fallback,
                                 String path, JSONObject body) throws Exception {
        Exception first = null;
        for (String base : new String[]{primary, fallback}) {
            if (base == null || base.isEmpty()) continue;
            try {
                return httpPost(base + path, body, null);
            } catch (Exception e) {
                if (first == null) first = e;
            }
        }
        throw first != null ? first : new Exception("no backend URL");
    }

    private JSONObject lanPostBest(JSONObject body) throws Exception {
        Exception last = null;
        String[] order = orderedIps();
        for (String ip : order) {
            try {
                JSONObject r = lanPost(ip, body);
                lanWorkingIp = ip;
                return r;
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new Exception("desktop unreachable on the LAN");
    }

    private String[] orderedIps() {
        if (lanWorkingIp == null || lanWorkingIp.isEmpty()) return lanIps;
        String[] out = new String[lanIps.length];
        out[0] = lanWorkingIp;
        int j = 1;
        for (String ip : lanIps) if (!ip.equals(lanWorkingIp)) out[j++] = ip;
        return out;
    }

    private JSONObject lanPost(String ip, JSONObject body) throws Exception {
        return httpPost("http://" + ip + ":" + lanPort + "/command", body,
                "Bearer " + lanToken);
    }

    private JSONObject httpPost(String url, JSONObject body, String auth)
            throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestProperty("Content-Type", "application/json");
            if (auth != null) c.setRequestProperty("Authorization", auth);
            c.setDoOutput(true);
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = c.getOutputStream()) {
                os.write(bytes);
            }
            int code = c.getResponseCode();
            InputStream is = code < 400 ? c.getInputStream() : c.getErrorStream();
            String text = readAll(is);
            if (code >= 400) throw new Exception("HTTP " + code + ": " + text);
            return new JSONObject(text);
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    private static void post(Callback cb, JSONObject resp, Exception err) {
        MAIN.post(() -> cb.onResult(resp, err));
    }
}
