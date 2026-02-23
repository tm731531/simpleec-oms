package com.simpleec.api.controller;

import com.simpleec.api.dto.AdminStatsResponse;
import com.simpleec.core.repository.AccountRepository;
import com.simpleec.core.repository.MerchantRepository;
import com.simpleec.core.repository.OrderRepository;
import com.simpleec.core.repository.PlatformRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 管理後台統計 API 控制器
 * NOTE: Platform, Merchant, and Account CRUD endpoints are now in dedicated controllers:
 * - AdminPlatformController handles /admin/platform*
 * - AdminMerchantController handles /admin/merchant*
 * - AdminAccountController handles /admin/account*
 */
@Slf4j
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {
    private final PlatformRepository platformRepository;
    private final AccountRepository accountRepository;
    private final MerchantRepository merchantRepository;
    private final OrderRepository orderRepository;

    // ======================== Stats Endpoint ========================

    /** GET /api/admin/stats */
    @GetMapping("/stats")
    public ResponseEntity<AdminStatsResponse> getStats() {
        long merchantCount = merchantRepository.count();
        long accountCount = accountRepository.count();
        long platformCount = platformRepository.count();
        long orderCount = orderRepository.count();

        return ResponseEntity.ok(AdminStatsResponse.success(
            merchantCount, accountCount, platformCount, orderCount));
    }
}
