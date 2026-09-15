package com.bloom.eventmanager.knowledge.api;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Routing smoke endpoint. Deliberately has no dependencies, so a failure here
 * always means the gateway or the registry is misconfigured, never that a
 * datastore is down.
 */
@RestController
@RequestMapping("/api/v1")
public class PingController {

    @GetMapping("/ping")
    public Map<String, String> ping() {
        return Map.of("service", "knowledge-service", "status", "ok");
    }
}
