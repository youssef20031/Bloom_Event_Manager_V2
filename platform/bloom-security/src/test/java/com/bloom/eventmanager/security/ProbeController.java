package com.bloom.eventmanager.security;

import java.util.Map;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProbeController {

    @GetMapping("/probe/secure")
    public Map<String, String> secure(Authentication authentication) {
        return Map.of("principal", authentication.getName());
    }

    @GetMapping("/probe/open")
    public Map<String, String> open() {
        return Map.of("status", "open");
    }
}
