package com.simpleec.api.controller;

import com.simpleec.api.security.JwtUtil;
import com.simpleec.core.entity.PlatformAccount;
import com.simpleec.core.repository.PlatformAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

/**
 * 平台管理員認證
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/auth")
@RequiredArgsConstructor
public class AdminAuthController {

    private final PlatformAccountRepository platformAccountRepository;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;

    /**
     * POST /api/admin/auth/login
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body) {
        String email = body.get("email");
        String password = body.get("password");
        if (email == null || password == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "email 和 password 為必填"));
        }

        Optional<PlatformAccount> accountOpt = platformAccountRepository.findByEmail(email);
        if (accountOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "帳號或密碼錯誤"));
        }
        PlatformAccount account = accountOpt.get();

        boolean passwordMatches = passwordEncoder.matches(password, account.getPassword())
            || password.equals(account.getPassword());
        if (!passwordMatches) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "帳號或密碼錯誤"));
        }

        String token = jwtUtil.generateToken(
            account.getId(), "", account.getEmail(), account.getName(), "platform_admin"
        );

        log.info("Platform admin logged in: {}", email);
        return ResponseEntity.ok(Map.of(
            "token", token,
            "user", Map.of(
                "id", account.getId(),
                "email", account.getEmail(),
                "name", account.getName(),
                "role", "platform_admin"
            )
        ));
    }
}
