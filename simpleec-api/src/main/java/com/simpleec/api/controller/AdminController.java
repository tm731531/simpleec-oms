package com.simpleec.api.controller;

import com.simpleec.api.dto.AdminPageResponse;
import com.simpleec.api.dto.AdminResponse;
import com.simpleec.api.dto.AdminStatsResponse;
import com.simpleec.common.util.NanoIdUtil;
import com.simpleec.core.entity.Account;
import com.simpleec.core.entity.Merchant;
import com.simpleec.core.entity.Platform;
import com.simpleec.core.repository.AccountRepository;
import com.simpleec.core.repository.MerchantRepository;
import com.simpleec.core.repository.OrderRepository;
import com.simpleec.core.repository.PlatformRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {
    private final PlatformRepository platformRepository;
    private final AccountRepository accountRepository;
    private final MerchantRepository merchantRepository;
    private final OrderRepository orderRepository;
    private final PasswordEncoder passwordEncoder;

    // ======================== Platform Endpoints ========================

    /** GET /api/admin/platform?page=1&pageSize=20 */
    @GetMapping("/platform")
    public ResponseEntity<AdminPageResponse<Platform>> listPlatforms(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        Page<Platform> platforms = platformRepository.findAll(PageRequest.of(page - 1, pageSize));
        return ResponseEntity.ok(AdminPageResponse.success(platforms));
    }

    /** GET /api/admin/platform/{id} */
    @GetMapping("/platform/{id}")
    public ResponseEntity<AdminResponse<Platform>> getPlatform(@PathVariable String id) {
        Optional<Platform> platform = platformRepository.findById(id);
        if (platform.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(AdminResponse.<Platform>builder()
                    .code(404)
                    .message("平台不存在")
                    .build());
        }
        return ResponseEntity.ok(AdminResponse.success(platform.get()));
    }

    /** POST /api/admin/platform */
    @PostMapping("/platform")
    public ResponseEntity<AdminResponse<Platform>> createPlatform(@RequestBody Map<String, Object> body) {
        Platform platform = Platform.builder()
            .id((String) body.get("id"))
            .platformName((String) body.get("platform_name"))
            .credential1((String) body.get("credential1"))
            .credential2((String) body.getOrDefault("credential2", ""))
            .actived(true)
            .queueTopic((String) body.getOrDefault("queue_topic", ""))
            .currency((String) body.getOrDefault("currency", "TWD"))
            .build();
        Platform saved = platformRepository.save(platform);
        return ResponseEntity.status(HttpStatus.CREATED).body(AdminResponse.success(saved));
    }

    /** PUT /api/admin/platform/{id} */
    @PutMapping("/platform/{id}")
    public ResponseEntity<AdminResponse<Platform>> updatePlatform(
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        Optional<Platform> platformOpt = platformRepository.findById(id);
        if (platformOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(AdminResponse.<Platform>builder()
                    .code(404)
                    .message("平台不存在")
                    .build());
        }
        Platform platform = platformOpt.get();
        if (body.containsKey("platform_name")) platform.setPlatformName((String) body.get("platform_name"));
        if (body.containsKey("credential1")) platform.setCredential1((String) body.get("credential1"));
        if (body.containsKey("credential2")) platform.setCredential2((String) body.get("credential2"));
        if (body.containsKey("queue_topic")) platform.setQueueTopic((String) body.get("queue_topic"));
        if (body.containsKey("currency")) platform.setCurrency((String) body.get("currency"));
        if (body.containsKey("actived")) platform.setActived((Boolean) body.get("actived"));
        Platform updated = platformRepository.save(platform);
        return ResponseEntity.ok(AdminResponse.success(updated));
    }

    /** DELETE /api/admin/platform/{id} */
    @DeleteMapping("/platform/{id}")
    public ResponseEntity<AdminResponse<Void>> deletePlatform(@PathVariable String id) {
        Optional<Platform> platform = platformRepository.findById(id);
        if (platform.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(AdminResponse.<Void>builder()
                    .code(404)
                    .message("平台不存在")
                    .build());
        }
        platformRepository.delete(platform.get());
        return ResponseEntity.ok(AdminResponse.success());
    }

    // ======================== Account Endpoints ========================

    /** GET /api/admin/account?page=1&pageSize=20&merchant_id=xxx */
    @GetMapping("/account")
    public ResponseEntity<AdminPageResponse<Account>> listAccounts(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String merchant_id) {
        Page<Account> accounts;
        if (merchant_id != null && !merchant_id.isEmpty()) {
            accounts = accountRepository.findByMerchantId(merchant_id, PageRequest.of(page - 1, pageSize));
        } else {
            accounts = accountRepository.findAll(PageRequest.of(page - 1, pageSize));
        }
        return ResponseEntity.ok(AdminPageResponse.success(accounts));
    }

    /** GET /api/admin/account/{id} */
    @GetMapping("/account/{id}")
    public ResponseEntity<AdminResponse<Account>> getAccount(@PathVariable String id) {
        Optional<Account> account = accountRepository.findById(id);
        if (account.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(AdminResponse.<Account>builder()
                    .code(404)
                    .message("帳戶不存在")
                    .build());
        }
        return ResponseEntity.ok(AdminResponse.success(account.get()));
    }

    /** POST /api/admin/account */
    @PostMapping("/account")
    public ResponseEntity<AdminResponse<Account>> createAccount(@RequestBody Map<String, Object> body) {
        String password = (String) body.get("account_password");
        if (password == null || password.isEmpty()) {
            return ResponseEntity.badRequest()
                .body(AdminResponse.<Account>builder()
                    .code(400)
                    .message("密碼為必填項")
                    .build());
        }

        // Generate Account ID: merchant(4) + yyyyMMddHHmmss(14) + random(2) = 20 chars
        String merchantId = (String) body.get("merchant_id");
        String merchantPrefix = merchantId != null && merchantId.length() >= 4 ? merchantId.substring(0, 4) : "XXXX";
        String timestamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String randomPart = NanoIdUtil.generate().substring(0, 2);
        String accountId = merchantPrefix + timestamp + randomPart;

        Account account = Account.builder()
            .id(accountId)
            .accountName((String) body.get("account_name"))
            .accountEmail((String) body.get("account_email"))
            .accountPassword(passwordEncoder.encode(password))
            .accountTel((String) body.getOrDefault("account_tel", ""))
            .isMainAccount((Boolean) body.getOrDefault("is_main_account", false))
            .accessLevel(((Number) body.getOrDefault("access_level", 0)).intValue())
            .merchantId((String) body.get("merchant_id"))
            .status((String) body.getOrDefault("status", "enable"))
            .createdAt(LocalDateTime.now())
            .build();
        Account saved = accountRepository.save(account);
        return ResponseEntity.status(HttpStatus.CREATED).body(AdminResponse.success(saved));
    }

    /** PUT /api/admin/account/{id} */
    @PutMapping("/account/{id}")
    public ResponseEntity<AdminResponse<Account>> updateAccount(
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        Optional<Account> accountOpt = accountRepository.findById(id);
        if (accountOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(AdminResponse.<Account>builder()
                    .code(404)
                    .message("帳戶不存在")
                    .build());
        }
        Account account = accountOpt.get();
        if (body.containsKey("account_name")) account.setAccountName((String) body.get("account_name"));
        if (body.containsKey("account_email")) account.setAccountEmail((String) body.get("account_email"));
        if (body.containsKey("account_tel")) account.setAccountTel((String) body.get("account_tel"));
        if (body.containsKey("access_level")) account.setAccessLevel(((Number) body.get("access_level")).intValue());
        if (body.containsKey("status")) account.setStatus((String) body.get("status"));
        account.setUpdatedAt(LocalDateTime.now());
        Account updated = accountRepository.save(account);
        return ResponseEntity.ok(AdminResponse.success(updated));
    }

    /** POST /api/admin/account/{id}/reset-password */
    @PostMapping("/account/{id}/reset-password")
    public ResponseEntity<AdminResponse<Void>> resetPassword(
            @PathVariable String id,
            @RequestBody Map<String, String> body) {
        Optional<Account> accountOpt = accountRepository.findById(id);
        if (accountOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(AdminResponse.<Void>builder()
                    .code(404)
                    .message("帳戶不存在")
                    .build());
        }
        String newPassword = body.get("password");
        if (newPassword == null || newPassword.isEmpty()) {
            return ResponseEntity.badRequest()
                .body(AdminResponse.<Void>builder()
                    .code(400)
                    .message("新密碼為必填項")
                    .build());
        }
        Account account = accountOpt.get();
        account.setAccountPassword(passwordEncoder.encode(newPassword));
        account.setUpdatedAt(LocalDateTime.now());
        accountRepository.save(account);
        return ResponseEntity.ok(AdminResponse.success());
    }

    /** DELETE /api/admin/account/{id} */
    @DeleteMapping("/account/{id}")
    public ResponseEntity<AdminResponse<Void>> deleteAccount(@PathVariable String id) {
        Optional<Account> account = accountRepository.findById(id);
        if (account.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(AdminResponse.<Void>builder()
                    .code(404)
                    .message("帳戶不存在")
                    .build());
        }
        accountRepository.delete(account.get());
        return ResponseEntity.ok(AdminResponse.success());
    }

    // ======================== Merchant Endpoints ========================

    /** GET /api/admin/merchant?page=1&pageSize=20 */
    @GetMapping("/merchant")
    public ResponseEntity<AdminPageResponse<Merchant>> listMerchants(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        Page<Merchant> merchants = merchantRepository.findAll(PageRequest.of(page - 1, pageSize));
        return ResponseEntity.ok(AdminPageResponse.success(merchants));
    }

    /** GET /api/admin/merchant/{id} */
    @GetMapping("/merchant/{id}")
    public ResponseEntity<AdminResponse<Merchant>> getMerchant(@PathVariable String id) {
        Optional<Merchant> merchant = merchantRepository.findById(id);
        if (merchant.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(AdminResponse.<Merchant>builder()
                    .code(404)
                    .message("商家不存在")
                    .build());
        }
        return ResponseEntity.ok(AdminResponse.success(merchant.get()));
    }

    /** POST /api/admin/merchant */
    @PostMapping("/merchant")
    public ResponseEntity<AdminResponse<Merchant>> createMerchant(@RequestBody Map<String, Object> body) {
        Merchant merchant = Merchant.builder()
            .id(NanoIdUtil.generate().substring(0, 6))
            .merchantName((String) body.get("merchant_name"))
            .merchantEmail((String) body.get("merchant_email"))
            .merchantPhoneNumber((String) body.getOrDefault("merchant_phone_number", ""))
            .taxIdNumber((String) body.getOrDefault("tax_id_number", ""))
            .addressCity((String) body.getOrDefault("address_city", ""))
            .addressRegion((String) body.getOrDefault("address_region", ""))
            .addressCountry((String) body.getOrDefault("address_country", ""))
            .addressZip((String) body.getOrDefault("address_zip", ""))
            .addressLine1((String) body.getOrDefault("address_line1", ""))
            .addressLine2((String) body.getOrDefault("address_line2", ""))
            .addressPhoneNumber((String) body.getOrDefault("address_phone_number", ""))
            .vipLevel(((Number) body.getOrDefault("vip_level", 0)).intValue())
            .userLocalTimeZone((String) body.getOrDefault("user_local_time_zone", "Asia/Taipei"))
            .payerName((String) body.getOrDefault("payer_name", ""))
            .payerEmail((String) body.getOrDefault("payer_email", ""))
            .payerPhoneNumber((String) body.getOrDefault("payer_phone_number", ""))
            .status((String) body.getOrDefault("status", "enable"))
            .createdAt(LocalDateTime.now())
            .build();
        Merchant saved = merchantRepository.save(merchant);
        return ResponseEntity.status(HttpStatus.CREATED).body(AdminResponse.success(saved));
    }

    /** PUT /api/admin/merchant/{id} */
    @PutMapping("/merchant/{id}")
    public ResponseEntity<AdminResponse<Merchant>> updateMerchant(
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        Optional<Merchant> merchantOpt = merchantRepository.findById(id);
        if (merchantOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(AdminResponse.<Merchant>builder()
                    .code(404)
                    .message("商家不存在")
                    .build());
        }
        Merchant merchant = merchantOpt.get();
        if (body.containsKey("merchant_name")) merchant.setMerchantName((String) body.get("merchant_name"));
        if (body.containsKey("merchant_email")) merchant.setMerchantEmail((String) body.get("merchant_email"));
        if (body.containsKey("merchant_phone_number")) merchant.setMerchantPhoneNumber((String) body.get("merchant_phone_number"));
        if (body.containsKey("tax_id_number")) merchant.setTaxIdNumber((String) body.get("tax_id_number"));
        if (body.containsKey("address_city")) merchant.setAddressCity((String) body.get("address_city"));
        if (body.containsKey("address_region")) merchant.setAddressRegion((String) body.get("address_region"));
        if (body.containsKey("address_country")) merchant.setAddressCountry((String) body.get("address_country"));
        if (body.containsKey("address_zip")) merchant.setAddressZip((String) body.get("address_zip"));
        if (body.containsKey("address_line1")) merchant.setAddressLine1((String) body.get("address_line1"));
        if (body.containsKey("address_line2")) merchant.setAddressLine2((String) body.get("address_line2"));
        if (body.containsKey("vip_level")) merchant.setVipLevel(((Number) body.get("vip_level")).intValue());
        if (body.containsKey("user_local_time_zone")) merchant.setUserLocalTimeZone((String) body.get("user_local_time_zone"));
        if (body.containsKey("payer_name")) merchant.setPayerName((String) body.get("payer_name"));
        if (body.containsKey("payer_email")) merchant.setPayerEmail((String) body.get("payer_email"));
        if (body.containsKey("payer_phone_number")) merchant.setPayerPhoneNumber((String) body.get("payer_phone_number"));
        if (body.containsKey("status")) merchant.setStatus((String) body.get("status"));
        merchant.setUpdatedAt(LocalDateTime.now());
        Merchant updated = merchantRepository.save(merchant);
        return ResponseEntity.ok(AdminResponse.success(updated));
    }

    /** DELETE /api/admin/merchant/{id} */
    @DeleteMapping("/merchant/{id}")
    public ResponseEntity<AdminResponse<Void>> deleteMerchant(@PathVariable String id) {
        Optional<Merchant> merchant = merchantRepository.findById(id);
        if (merchant.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(AdminResponse.<Void>builder()
                    .code(404)
                    .message("商家不存在")
                    .build());
        }
        merchantRepository.delete(merchant.get());
        return ResponseEntity.ok(AdminResponse.success());
    }

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
