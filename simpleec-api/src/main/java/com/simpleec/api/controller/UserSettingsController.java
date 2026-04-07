package com.simpleec.api.controller;

import com.simpleec.api.security.UserPrincipal;
import com.simpleec.core.entity.Merchant;
import com.simpleec.core.repository.MerchantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.Optional;

/**
 * 用戶設定控制器
 */
@RestController
@RequestMapping("/api/user/settings")
@RequiredArgsConstructor
public class UserSettingsController {
    private final MerchantRepository merchantRepository;

    @GetMapping
    public ResponseEntity<Merchant> getSettings(@AuthenticationPrincipal UserPrincipal principal) {
        Optional<Merchant> merchant = merchantRepository.findById(principal.getMerchantId());
        return merchant.map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @PatchMapping
    public ResponseEntity<Merchant> updateSettings(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody Map<String, String> updates) {
        Optional<Merchant> merchantOpt = merchantRepository.findById(principal.getMerchantId());
        if (merchantOpt.isEmpty()) return ResponseEntity.notFound().build();
        Merchant merchant = merchantOpt.get();
        if (updates.containsKey("merchantName")) merchant.setMerchantName(updates.get("merchantName"));
        if (updates.containsKey("merchantEmail")) merchant.setMerchantEmail(updates.get("merchantEmail"));
        if (updates.containsKey("merchantPhoneNumber")) merchant.setMerchantPhoneNumber(updates.get("merchantPhoneNumber"));
        if (updates.containsKey("taxIdNumber")) merchant.setTaxIdNumber(updates.get("taxIdNumber"));
        if (updates.containsKey("userLocalTimeZone")) merchant.setUserLocalTimeZone(updates.get("userLocalTimeZone"));
        return ResponseEntity.ok(merchantRepository.save(merchant));
    }
}
