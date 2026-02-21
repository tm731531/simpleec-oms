package com.simpleec.api.controller;

import com.simpleec.api.security.JwtUtil;
import com.simpleec.api.security.UserPrincipal;
import com.simpleec.core.entity.Account;
import com.simpleec.core.entity.Merchant;
import com.simpleec.core.repository.AccountRepository;
import com.simpleec.core.repository.MerchantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.Optional;

/**
 * 認證控制器
 */
@Slf4j
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AccountRepository accountRepository;
    private final MerchantRepository merchantRepository;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;

    /**
     * POST /api/auth/login
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body) {
        String email = body.get("email");
        String password = body.get("password");
        if (email == null || password == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "email 和 password 為必填"));
        }

        Optional<Account> accountOpt = accountRepository.findByAccountEmail(email);
        if (accountOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "帳號或密碼錯誤"));
        }
        Account account = accountOpt.get();
        if (!"enable".equals(account.getStatus())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "帳號已停用"));
        }
        // Support both BCrypt-hashed and plain-text passwords (for backward compatibility)
        boolean passwordMatches = passwordEncoder.matches(password, account.getAccountPassword())
            || password.equals(account.getAccountPassword());

        if (!passwordMatches) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "帳號或密碼錯誤"));
        }

        String role = Boolean.TRUE.equals(account.getIsMainAccount()) ? "main" : "sub";
        String token = jwtUtil.generateToken(
            account.getId(), account.getMerchantId(),
            account.getAccountEmail(), account.getAccountName(), role
        );

        Optional<Merchant> merchantOpt = merchantRepository.findById(account.getMerchantId());
        String merchantName = merchantOpt.map(Merchant::getMerchantName).orElse("");

        log.info("User logged in: {}", email);
        return ResponseEntity.ok(Map.of(
            "token", token,
            "user", Map.of(
                "id", account.getId(),
                "email", account.getAccountEmail(),
                "name", account.getAccountName(),
                "merchantId", account.getMerchantId(),
                "merchantName", merchantName,
                "role", role
            )
        ));
    }

    /**
     * GET /api/auth/me
     */
    @GetMapping("/me")
    public ResponseEntity<?> me(@AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "未授權"));
        }
        return ResponseEntity.ok(Map.of(
            "id", principal.getAccountId(),
            "email", principal.getEmail(),
            "name", principal.getName(),
            "merchantId", principal.getMerchantId(),
            "role", principal.getRole()
        ));
    }

    /**
     * POST /api/auth/logout (stateless JWT — just return 200)
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        return ResponseEntity.ok().build();
    }
}
