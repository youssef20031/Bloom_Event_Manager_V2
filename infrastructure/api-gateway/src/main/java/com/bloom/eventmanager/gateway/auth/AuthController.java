package com.bloom.eventmanager.gateway.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record LoginResponse(String token, String tokenType, long expiresIn) {
    }

    private final JwtIssuer issuer;
    private final AuthProperties properties;

    public AuthController(JwtIssuer issuer, AuthProperties properties) {
        this.issuer = issuer;
        this.properties = properties;
    }

    @PostMapping("/api/v1/auth/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        if (!issuer.credentialsMatch(request.username(), request.password())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ProblemDetailEntryPoint.unauthenticated("Invalid credentials"));
        }
        return ResponseEntity.ok(new LoginResponse(issuer.issue(request.username()), "Bearer", properties.tokenTtl().toSeconds()));
    }
}
