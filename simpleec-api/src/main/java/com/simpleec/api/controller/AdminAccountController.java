package com.simpleec.api.controller;

import com.simpleec.api.dto.AdminApiResponse;
import com.simpleec.api.dto.PageResponse;
import com.simpleec.core.entity.Account;
import com.simpleec.core.entity.Merchant;
import com.simpleec.core.repository.AccountRepository;
import com.simpleec.core.repository.MerchantRepository;
import com.simpleec.common.util.NanoIdUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 管理後台 - 帳號管理 API 控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/account")
@RequiredArgsConstructor
@CrossOrigin(origins = {"http://localhost:8080", "http://localhost:8081", "http://localhost:8082",
                        "http://192.168.0.48:8080", "https://oms.tomting.com", "http://oms.tomting.com"},
             methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.OPTIONS},
             allowedHeaders = {"Content-Type", "Authorization"})
public class AdminAccountController {

    private final AccountRepository accountRepository;
    private final MerchantRepository merchantRepository;
    private final PasswordEncoder passwordEncoder;

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final String EMAIL_REGEX = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,6}$";
    private static final Pattern EMAIL_PATTERN = Pattern.compile(EMAIL_REGEX);

    /**
     * GET /api/admin/account
     * 查詢帳號列表（分頁）
     *
     * @param page 頁碼（1-based）
     * @param pageSize 每頁記錄數（預設 20，最大 100）
     * @param merchantId 篩選商家 ID（可選）
     * @param search 搜尋關鍵字（帳號名稱或信箱）
     * @param sortBy 排序欄位（created_at, account_name 等）
     * @param order 排序順序（asc 或 desc）
     */
    @GetMapping
    public ResponseEntity<AdminApiResponse<PageResponse<Account>>> listAccounts(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer pageSize,
            @RequestParam(required = false) String merchantId,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "created_at") String sortBy,
            @RequestParam(defaultValue = "desc") String order) {

        try {
            // 驗證分頁參數
            if (page < 1) page = 1;
            if (pageSize < 1) pageSize = 1;
            if (pageSize > MAX_PAGE_SIZE) pageSize = MAX_PAGE_SIZE;

            // 建構排序物件
            Sort.Direction direction = "asc".equalsIgnoreCase(order) ? Sort.Direction.ASC : Sort.Direction.DESC;
            Pageable pageable = PageRequest.of(page - 1, pageSize, Sort.by(direction, sortBy));

            // 查詢資料
            Page<Account> result;
            if (merchantId != null && !merchantId.trim().isEmpty()) {
                if (search != null && !search.trim().isEmpty()) {
                    result = accountRepository.searchByMerchantIdAndKeyword(merchantId, search.trim(), pageable);
                    log.info("Searched accounts by merchantId: {}, keyword: {}, found {} records", merchantId, search, result.getTotalElements());
                } else {
                    result = accountRepository.findByMerchantId(merchantId, pageable);
                    log.info("Listed accounts for merchant: {}, found {} records", merchantId, result.getTotalElements());
                }
            } else {
                result = accountRepository.findAll(pageable);
                log.info("Listed all accounts, page: {}, size: {}, total: {}", page, pageSize, result.getTotalElements());
            }

            return ResponseEntity.ok(AdminApiResponse.success(PageResponse.fromPage(result)));
        } catch (Exception e) {
            log.error("Error listing accounts", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(AdminApiResponse.serverError("Failed to list accounts: " + e.getMessage()));
        }
    }

    /**
     * GET /api/admin/account/{id}
     * 查詢單筆帳號詳細資訊
     */
    @GetMapping("/{id}")
    public ResponseEntity<AdminApiResponse<Account>> getAccount(@PathVariable String id) {
        try {
            Optional<Account> account = accountRepository.findById(id);
            if (account.isPresent()) {
                log.info("Retrieved account: {}", id);
                return ResponseEntity.ok(AdminApiResponse.success(account.get()));
            } else {
                log.warn("Account not found: {}", id);
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(AdminApiResponse.notFound("Account not found: " + id));
            }
        } catch (Exception e) {
            log.error("Error retrieving account {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(AdminApiResponse.serverError("Failed to retrieve account: " + e.getMessage()));
        }
    }

    /**
     * POST /api/admin/account
     * 建立新帳號
     */
    @PostMapping
    public ResponseEntity<AdminApiResponse<Account>> createAccount(@RequestBody Account account) {
        try {
            // 驗證必填欄位
            if (account.getAccountEmail() == null || account.getAccountEmail().trim().isEmpty()) {
                log.warn("Invalid account: missing account_email");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(AdminApiResponse.badRequest("Account email is required"));
            }

            if (account.getAccountPassword() == null || account.getAccountPassword().trim().isEmpty()) {
                log.warn("Invalid account: missing account_password");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(AdminApiResponse.badRequest("Account password is required"));
            }

            if (account.getMerchantId() == null || account.getMerchantId().trim().isEmpty()) {
                log.warn("Invalid account: missing merchant_id");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(AdminApiResponse.badRequest("Merchant ID is required"));
            }

            // 驗證信箱格式
            if (!EMAIL_PATTERN.matcher(account.getAccountEmail()).matches()) {
                log.warn("Invalid account email format: {}", account.getAccountEmail());
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(AdminApiResponse.badRequest("Invalid email format: " + account.getAccountEmail()));
            }

            // 驗證密碼長度
            if (account.getAccountPassword().length() < MIN_PASSWORD_LENGTH) {
                log.warn("Invalid account: password too short");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(AdminApiResponse.badRequest("Password must be at least " + MIN_PASSWORD_LENGTH + " characters"));
            }

            // 檢查商家是否存在
            if (!merchantRepository.existsById(account.getMerchantId())) {
                log.warn("Merchant not found: {}", account.getMerchantId());
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(AdminApiResponse.badRequest("Merchant not found: " + account.getMerchantId()));
            }

            // 檢查信箱是否已存在
            if (accountRepository.findByAccountEmail(account.getAccountEmail()).isPresent()) {
                log.warn("Duplicate account email: {}", account.getAccountEmail());
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(AdminApiResponse.conflict("Account email already exists: " + account.getAccountEmail()));
            }

            // 生成 ID 並加密密碼
            account.setId(NanoIdUtil.generate());
            account.setAccountPassword(passwordEncoder.encode(account.getAccountPassword()));

            // 儲存帳號
            Account created = accountRepository.save(account);
            log.info("Created account: {} for merchant: {}", created.getId(), created.getMerchantId());

            // 不返回密碼
            created.setAccountPassword(null);

            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(AdminApiResponse.success(created));
        } catch (Exception e) {
            log.error("Error creating account", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(AdminApiResponse.serverError("Failed to create account: " + e.getMessage()));
        }
    }

    /**
     * PUT /api/admin/account/{id}
     * 更新帳號資訊
     */
    @PutMapping("/{id}")
    public ResponseEntity<AdminApiResponse<Account>> updateAccount(
            @PathVariable String id,
            @RequestBody Account accountUpdate) {

        try {
            Optional<Account> existing = accountRepository.findById(id);
            if (!existing.isPresent()) {
                log.warn("Account not found for update: {}", id);
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(AdminApiResponse.notFound("Account not found: " + id));
            }

            Account account = existing.get();

            // 更新允許修改的欄位
            if (accountUpdate.getAccountName() != null) {
                account.setAccountName(accountUpdate.getAccountName());
            }

            if (accountUpdate.getAccountEmail() != null && !accountUpdate.getAccountEmail().trim().isEmpty()) {
                String newEmail = accountUpdate.getAccountEmail();
                // 驗證信箱格式
                if (!EMAIL_PATTERN.matcher(newEmail).matches()) {
                    log.warn("Invalid account email format: {}", newEmail);
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                            .body(AdminApiResponse.badRequest("Invalid email format: " + newEmail));
                }
                // 檢查新信箱是否已被他人使用
                Optional<Account> existingEmail = accountRepository.findByAccountEmail(newEmail);
                if (existingEmail.isPresent() && !existingEmail.get().getId().equals(id)) {
                    log.warn("Duplicate account email: {}", newEmail);
                    return ResponseEntity.status(HttpStatus.CONFLICT)
                            .body(AdminApiResponse.conflict("Account email already exists: " + newEmail));
                }
                account.setAccountEmail(newEmail);
            }

            if (accountUpdate.getAccountTel() != null) {
                account.setAccountTel(accountUpdate.getAccountTel());
            }

            if (accountUpdate.getIsMainAccount() != null) {
                account.setIsMainAccount(accountUpdate.getIsMainAccount());
            }

            if (accountUpdate.getAccessLevel() != null) {
                account.setAccessLevel(accountUpdate.getAccessLevel());
            }

            if (accountUpdate.getStatus() != null) {
                account.setStatus(accountUpdate.getStatus());
            }

            // 儲存更新
            Account updated = accountRepository.save(account);
            log.info("Updated account: {}", id);

            // 不返回密碼
            updated.setAccountPassword(null);

            return ResponseEntity.ok(AdminApiResponse.success(updated));
        } catch (Exception e) {
            log.error("Error updating account {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(AdminApiResponse.serverError("Failed to update account: " + e.getMessage()));
        }
    }

    /**
     * DELETE /api/admin/account/{id}
     * 刪除帳號
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<AdminApiResponse<Void>> deleteAccount(@PathVariable String id) {
        try {
            Optional<Account> existing = accountRepository.findById(id);
            if (!existing.isPresent()) {
                log.warn("Account not found for deletion: {}", id);
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(AdminApiResponse.notFound("Account not found: " + id));
            }

            accountRepository.deleteById(id);
            log.info("Deleted account: {}", id);

            return ResponseEntity.ok(AdminApiResponse.success());
        } catch (Exception e) {
            log.error("Error deleting account {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(AdminApiResponse.serverError("Failed to delete account: " + e.getMessage()));
        }
    }

    /**
     * POST /api/admin/account/{id}/reset-password
     * 重設帳號密碼
     *
     * 請求體：
     * {
     *   "newPassword": "new_password_123"
     * }
     */
    @PostMapping("/{id}/reset-password")
    public ResponseEntity<AdminApiResponse<Void>> resetPassword(
            @PathVariable String id,
            @RequestBody java.util.Map<String, String> requestBody) {

        try {
            Optional<Account> existing = accountRepository.findById(id);
            if (!existing.isPresent()) {
                log.warn("Account not found for password reset: {}", id);
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(AdminApiResponse.notFound("Account not found: " + id));
            }

            String newPassword = requestBody.get("newPassword");
            if (newPassword == null || newPassword.trim().isEmpty()) {
                log.warn("Invalid password reset request: missing newPassword");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(AdminApiResponse.badRequest("New password is required"));
            }

            // 驗證密碼長度
            if (newPassword.length() < MIN_PASSWORD_LENGTH) {
                log.warn("Invalid password: too short");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(AdminApiResponse.badRequest("Password must be at least " + MIN_PASSWORD_LENGTH + " characters"));
            }

            // 更新密碼
            Account account = existing.get();
            account.setAccountPassword(passwordEncoder.encode(newPassword));
            accountRepository.save(account);
            log.info("Reset password for account: {}", id);

            return ResponseEntity.ok(AdminApiResponse.success());
        } catch (Exception e) {
            log.error("Error resetting password for account {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(AdminApiResponse.serverError("Failed to reset password: " + e.getMessage()));
        }
    }
}
