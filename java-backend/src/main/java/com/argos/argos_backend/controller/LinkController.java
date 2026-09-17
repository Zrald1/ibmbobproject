package com.argos.argos_backend.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.argos.argos_backend.service.LinkService;

/**
 * Phone <-> desktop command relay — same contract as the Python backend's
 * /api/link/* routes so the Android app can fall back to this service
 * transparently.
 */
@RestController
@RequestMapping("/api/link")
public class LinkController {

    private final LinkService linkService;

    public LinkController(LinkService linkService) {
        this.linkService = linkService;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(linkService.health());
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody Map<String, Object> body) {
        return wrap(() -> linkService.register(
                (String) body.get("name"),
                (String) body.get("desktop_id"),
                (String) body.get("desktop_token")));
    }

    @PostMapping("/pair")
    public ResponseEntity<Map<String, Object>> pair(@RequestBody Map<String, Object> body) {
        return wrap(() -> linkService.pair(
                (String) body.get("desktop_id"),
                (String) body.get("pair_code"),
                (String) body.get("phone_name")));
    }

    @PostMapping("/command")
    public ResponseEntity<Map<String, Object>> command(@RequestBody Map<String, Object> body) {
        return wrap(() -> linkService.command(
                (String) body.get("desktop_id"),
                (String) body.get("phone_token"),
                (String) body.get("method"),
                (Map<String, Object>) body.get("params")));
    }

    @GetMapping("/poll")
    public ResponseEntity<Map<String, Object>> poll(@RequestParam("desktop_id") String desktopId,
                                                    @RequestParam("token") String token) {
        return wrap(() -> linkService.poll(desktopId, token));
    }

    @PostMapping("/result")
    public ResponseEntity<Map<String, Object>> result(@RequestBody Map<String, Object> body) {
        return wrap(() -> linkService.result(
                (String) body.get("desktop_id"),
                (String) body.get("token"),
                (String) body.get("command_id"),
                Boolean.TRUE.equals(body.get("ok")),
                (Map<String, Object>) body.get("result"),
                (String) body.get("error")));
    }

    @PostMapping("/status")
    public ResponseEntity<Map<String, Object>> status(@RequestBody Map<String, Object> body) {
        return wrap(() -> linkService.status(
                (String) body.get("desktop_id"),
                (String) body.get("phone_token"),
                (String) body.get("command_id")));
    }

    @PostMapping("/revoke")
    public ResponseEntity<Map<String, Object>> revoke(@RequestBody Map<String, Object> body) {
        return wrap(() -> linkService.revoke(
                (String) body.get("desktop_id"),
                (String) body.get("token")));
    }

    private ResponseEntity<Map<String, Object>> wrap(java.util.function.Supplier<Map<String, Object>> fn) {
        try {
            return ResponseEntity.ok(fn.get());
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.GONE, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }
}
