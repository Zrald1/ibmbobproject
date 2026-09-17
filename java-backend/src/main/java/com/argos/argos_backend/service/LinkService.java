package com.argos.argos_backend.service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

/**
 * Phone <-> desktop command relay — mirrors backend/app/routes/link.py.
 *
 * The desktop registers and polls; the phone pairs via the QR pair_code and
 * queues commands. In-memory state is intentional: this fallback backend uses
 * in-memory H2 anyway, and desktops re-register on their next poll cycle.
 */
@Service
public class LinkService {

    private static final long PAIR_TTL_SECONDS = 600;
    private static final long COMMAND_TTL_SECONDS = 300;
    private static final long POLL_ONLINE_SECONDS = 15;

    private final SecureRandom rng = new SecureRandom();

    public static class Command {
        public String id;
        public String method;
        public Map<String, Object> params;
        public String state = "queued";   // queued -> delivered -> done | error
        public Map<String, Object> result;
        public long createdAt = Instant.now().getEpochSecond();
        public Long deliveredAt;
    }

    public static class Desktop {
        public String name;
        public String token;
        public String pairCode;
        public long pairExpires;
        public final Map<String, String> phones = new ConcurrentHashMap<>(); // phoneToken -> name
        public final List<String> queue = new ArrayList<>();                 // pending command ids
        public final Map<String, Command> commands = new ConcurrentHashMap<>();
        public Long lastPollAt;
    }

    private final Map<String, Desktop> desktops = new ConcurrentHashMap<>();

    private String hex(int bytes) {
        byte[] b = new byte[bytes];
        rng.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    private void purge(Desktop d) {
        long now = Instant.now().getEpochSecond();
        d.commands.entrySet().removeIf(e -> {
            Command c = e.getValue();
            boolean dead = now - c.createdAt > COMMAND_TTL_SECONDS
                    || (("done".equals(c.state) || "error".equals(c.state))
                        && now - (c.deliveredAt != null ? c.deliveredAt : c.createdAt) > COMMAND_TTL_SECONDS);
            if (dead) d.queue.remove(e.getKey());
            return dead;
        });
    }

    private String newPairCode(Desktop d) {
        d.pairCode = hex(4);
        d.pairExpires = Instant.now().getEpochSecond() + PAIR_TTL_SECONDS;
        return d.pairCode;
    }

    public Map<String, Object> health() {
        long now = Instant.now().getEpochSecond();
        long online = desktops.values().stream()
                .filter(d -> d.lastPollAt != null && now - d.lastPollAt < POLL_ONLINE_SECONDS)
                .count();
        return Map.of("ok", true, "desktops", desktops.size(), "desktops_online", online);
    }

    public synchronized Map<String, Object> register(String name, String desktopId, String desktopToken) {
        if (desktopId != null && desktopToken != null) {
            Desktop d = desktops.get(desktopId);
            if (d != null && desktopToken.equals(d.token)) {
                d.name = name;
                newPairCode(d);   // old QR stops working on restart
                purge(d);
                return Map.of(
                        "ok", true,
                        "desktop_id", desktopId,
                        "desktop_token", d.token,
                        "pair_code", d.pairCode,
                        "pair_expires", d.pairExpires,
                        "reused", true);
            }
        }
        String id = "D-" + hex(4).toUpperCase();
        Desktop d = new Desktop();
        d.name = name;
        d.token = "dt_" + hex(18);
        newPairCode(d);
        desktops.put(id, d);
        return Map.of(
                "ok", true,
                "desktop_id", id,
                "desktop_token", d.token,
                "pair_code", d.pairCode,
                "pair_expires", d.pairExpires,
                "reused", false);
    }

    public synchronized Map<String, Object> pair(String desktopId, String pairCode, String phoneName) {
        Desktop d = desktops.get(desktopId);
        if (d == null) throw new IllegalArgumentException("unknown desktop_id");
        if (Instant.now().getEpochSecond() > d.pairExpires)
            throw new IllegalStateException("pair code expired — rescan the QR");
        if (pairCode == null || !pairCode.trim().equals(d.pairCode))
            throw new SecurityException("invalid pair code");
        String phoneToken = "pt_" + hex(18);
        d.phones.put(phoneToken, phoneName == null ? "Argos phone" : phoneName);
        return Map.of(
                "ok", true,
                "phone_token", phoneToken,
                "desktop_id", desktopId,
                "desktop_name", d.name);
    }

    public synchronized Map<String, Object> command(String desktopId, String phoneToken,
                                                    String method, Map<String, Object> params) {
        Desktop d = desktops.get(desktopId);
        if (d == null) throw new IllegalArgumentException("unknown desktop_id");
        if (phoneToken == null || !d.phones.containsKey(phoneToken))
            throw new SecurityException("unpaired phone");
        purge(d);
        Command c = new Command();
        c.id = "C-" + hex(6).toUpperCase();
        c.method = method;
        c.params = params;
        d.commands.put(c.id, c);
        d.queue.add(c.id);
        boolean online = d.lastPollAt != null
                && Instant.now().getEpochSecond() - d.lastPollAt < POLL_ONLINE_SECONDS;
        return Map.of("ok", true, "command_id", c.id, "queued", true, "desktop_online", online);
    }

    public synchronized Map<String, Object> poll(String desktopId, String token) {
        Desktop d = desktops.get(desktopId);
        if (d == null) throw new IllegalArgumentException("unknown desktop_id");
        if (token == null || !token.equals(d.token)) throw new SecurityException("invalid desktop token");
        d.lastPollAt = Instant.now().getEpochSecond();
        purge(d);
        List<Map<String, Object>> out = new ArrayList<>();
        for (String cid : d.queue) {
            Command c = d.commands.get(cid);
            if (c == null || !"queued".equals(c.state)) continue;
            c.state = "delivered";
            c.deliveredAt = Instant.now().getEpochSecond();
            out.add(Map.of("id", c.id, "method", c.method,
                           "params", c.params == null ? Map.of() : c.params));
        }
        d.queue.removeIf(cid -> {
            Command c = d.commands.get(cid);
            return c == null || !"queued".equals(c.state);
        });
        return Map.of("ok", true, "commands", out);
    }

    public synchronized Map<String, Object> result(String desktopId, String token, String commandId,
                                                   boolean ok, Map<String, Object> result, String error) {
        Desktop d = desktops.get(desktopId);
        if (d == null) throw new IllegalArgumentException("unknown desktop_id");
        if (token == null || !token.equals(d.token)) throw new SecurityException("invalid desktop token");
        Command c = d.commands.get(commandId);
        if (c == null) throw new IllegalArgumentException("unknown command_id");
        c.state = ok ? "done" : "error";
        c.result = ok ? result : Map.of("error", error == null ? "failed" : error);
        d.queue.remove(commandId);
        return Map.of("ok", true);
    }

    public synchronized Map<String, Object> status(String desktopId, String phoneToken, String commandId) {
        Desktop d = desktops.get(desktopId);
        if (d == null) throw new IllegalArgumentException("unknown desktop_id");
        if (phoneToken == null || !d.phones.containsKey(phoneToken))
            throw new SecurityException("unpaired phone");
        Command c = d.commands.get(commandId);
        boolean online = d.lastPollAt != null
                && Instant.now().getEpochSecond() - d.lastPollAt < POLL_ONLINE_SECONDS;
        if (c == null)
            return Map.of("ok", true, "state", "expired", "desktop_online", online);
        if ("done".equals(c.state) || "error".equals(c.state))
            return Map.of("ok", true, "state", c.state, "desktop_online", online,
                          "result", c.result == null ? Map.of() : c.result);
        return Map.of("ok", true, "state", c.state, "desktop_online", online);
    }

    public synchronized Map<String, Object> revoke(String desktopId, String token) {
        Desktop d = desktops.get(desktopId);
        if (d == null) throw new IllegalArgumentException("unknown desktop_id");
        if (token == null || !token.equals(d.token)) throw new SecurityException("invalid desktop token");
        d.phones.clear();
        newPairCode(d);
        return Map.of("ok", true, "pair_code", d.pairCode, "pair_expires", d.pairExpires);
    }
}
