package com.simpleec.api.controller;

import com.simpleec.api.dto.AdminApiResponse;
import com.simpleec.api.dto.PageResponse;
import com.simpleec.core.entity.Merchant;
import com.simpleec.core.repository.MerchantRepository;
import com.simpleec.common.util.NanoIdUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 管理後台 - 商家管理 API 控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/merchant")
@RequiredArgsConstructor
@CrossOrigin(origins = {"http://localhost:8080", "http://localhost:8081", "http://localhost:8082",
                        "http://192.168.0.48:8080", "https://oms.tomting.com", "http://oms.tomting.com"},
             methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.OPTIONS},
             allowedHeaders = {"Content-Type", "Authorization"})
public class AdminMerchantController {

    private final MerchantRepository merchantRepository;
    private static final int MAX_PAGE_SIZE = 100;
    private static final String EMAIL_REGEX = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,6}$";
    private static final Pattern EMAIL_PATTERN = Pattern.compile(EMAIL_REGEX);

    /**
     * GET /api/admin/merchant
     * 查詢商家列表（分頁）
     *
     * @param page 頁碼（1-based）
     * @param pageSize 每頁記錄數（預設 20，最大 100）
     * @param search 搜尋關鍵字（商家名稱或信箱）
     * @param sortBy 排序欄位（created_at, merchant_name 等）
     * @param order 排序順序（asc 或 desc）
     */
    @GetMapping
    public ResponseEntity<AdminApiResponse<PageResponse<Merchant>>> listMerchants(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer pageSize,
            @RequestParam(required = false) String search) {

        try {
            // 驗證分頁參數
            if (page < 1) page = 1;
            if (pageSize < 1) pageSize = 1;
            if (pageSize > MAX_PAGE_SIZE) pageSize = MAX_PAGE_SIZE;

            // 使用簡單分頁，不使用排序以避免欄位對應問題
            Pageable pageable = PageRequest.of(page - 1, pageSize);

            // 查詢資料
            Page<Merchant> result;
            if (search != null && !search.trim().isEmpty()) {
                result = merchantRepository.searchByKeyword(search.trim(), pageable);
                log.info("Searched merchants with keyword: {}, found {} records", search, result.getTotalElements());
            } else {
                result = merchantRepository.findAll(pageable);
                log.info("Listed merchants, page: {}, size: {}, total: {}", page, pageSize, result.getTotalElements());
            }

            return ResponseEntity.ok(AdminApiResponse.success(PageResponse.fromPage(result)));
        } catch (Exception e) {
            log.error("Error listing merchants", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(AdminApiResponse.serverError("Failed to list merchants: " + e.getMessage()));
        }
    }

    /**
     * GET /api/admin/merchant/{id}
     * 查詢單筆商家詳細資訊
     */
    @GetMapping("/{id}")
    public ResponseEntity<AdminApiResponse<Merchant>> getMerchant(@PathVariable String id) {
        try {
            Optional<Merchant> merchant = merchantRepository.findById(id);
            if (merchant.isPresent()) {
                log.info("Retrieved merchant: {}", id);
                return ResponseEntity.ok(AdminApiResponse.success(merchant.get()));
            } else {
                log.warn("Merchant not found: {}", id);
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(AdminApiResponse.notFound("Merchant not found: " + id));
            }
        } catch (Exception e) {
            log.error("Error retrieving merchant {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(AdminApiResponse.serverError("Failed to retrieve merchant: " + e.getMessage()));
        }
    }

    /**
     * POST /api/admin/merchant
     * 建立新商家
     */
    @PostMapping
    public ResponseEntity<AdminApiResponse<Merchant>> createMerchant(@RequestBody Merchant merchant) {
        try {
            // 驗證必填欄位
            if (merchant.getMerchantName() == null || merchant.getMerchantName().trim().isEmpty()) {
                log.warn("Invalid merchant: missing merchant_name");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(AdminApiResponse.badRequest("Merchant name is required"));
            }

            if (merchant.getMerchantEmail() == null || merchant.getMerchantEmail().trim().isEmpty()) {
                log.warn("Invalid merchant: missing merchant_email");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(AdminApiResponse.badRequest("Merchant email is required"));
            }

            // 驗證信箱格式
            if (!EMAIL_PATTERN.matcher(merchant.getMerchantEmail()).matches()) {
                log.warn("Invalid merchant email format: {}", merchant.getMerchantEmail());
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(AdminApiResponse.badRequest("Invalid email format: " + merchant.getMerchantEmail()));
            }

            // 檢查信箱是否已存在
            if (merchantRepository.findByMerchantEmail(merchant.getMerchantEmail()).isPresent()) {
                log.warn("Duplicate merchant email: {}", merchant.getMerchantEmail());
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(AdminApiResponse.conflict("Merchant email already exists: " + merchant.getMerchantEmail()));
            }

            // 生成 ID - 6 碼隨機數
            merchant.setId(NanoIdUtil.generate().substring(0, 6));

            // 儲存商家
            Merchant created = merchantRepository.save(merchant);
            log.info("Created merchant: {}", created.getId());

            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(AdminApiResponse.success(created));
        } catch (Exception e) {
            log.error("Error creating merchant", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(AdminApiResponse.serverError("Failed to create merchant: " + e.getMessage()));
        }
    }

    /**
     * PUT /api/admin/merchant/{id}
     * 更新商家資訊
     */
    @PutMapping("/{id}")
    public ResponseEntity<AdminApiResponse<Merchant>> updateMerchant(
            @PathVariable String id,
            @RequestBody Merchant merchantUpdate) {

        try {
            Optional<Merchant> existing = merchantRepository.findById(id);
            if (!existing.isPresent()) {
                log.warn("Merchant not found for update: {}", id);
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(AdminApiResponse.notFound("Merchant not found: " + id));
            }

            Merchant merchant = existing.get();

            // 更新允許修改的欄位
            if (merchantUpdate.getMerchantName() != null && !merchantUpdate.getMerchantName().trim().isEmpty()) {
                merchant.setMerchantName(merchantUpdate.getMerchantName());
            }

            if (merchantUpdate.getMerchantEmail() != null && !merchantUpdate.getMerchantEmail().trim().isEmpty()) {
                String newEmail = merchantUpdate.getMerchantEmail();
                // 驗證信箱格式
                if (!EMAIL_PATTERN.matcher(newEmail).matches()) {
                    log.warn("Invalid merchant email format: {}", newEmail);
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                            .body(AdminApiResponse.badRequest("Invalid email format: " + newEmail));
                }
                // 檢查新信箱是否已被他人使用
                Optional<Merchant> existingEmail = merchantRepository.findByMerchantEmail(newEmail);
                if (existingEmail.isPresent() && !existingEmail.get().getId().equals(id)) {
                    log.warn("Duplicate merchant email: {}", newEmail);
                    return ResponseEntity.status(HttpStatus.CONFLICT)
                            .body(AdminApiResponse.conflict("Merchant email already exists: " + newEmail));
                }
                merchant.setMerchantEmail(newEmail);
            }

            if (merchantUpdate.getMerchantPhoneNumber() != null) {
                merchant.setMerchantPhoneNumber(merchantUpdate.getMerchantPhoneNumber());
            }

            if (merchantUpdate.getTaxIdNumber() != null) {
                merchant.setTaxIdNumber(merchantUpdate.getTaxIdNumber());
            }

            if (merchantUpdate.getAddressCity() != null) {
                merchant.setAddressCity(merchantUpdate.getAddressCity());
            }

            if (merchantUpdate.getAddressRegion() != null) {
                merchant.setAddressRegion(merchantUpdate.getAddressRegion());
            }

            if (merchantUpdate.getAddressCountry() != null) {
                merchant.setAddressCountry(merchantUpdate.getAddressCountry());
            }

            if (merchantUpdate.getAddressZip() != null) {
                merchant.setAddressZip(merchantUpdate.getAddressZip());
            }

            if (merchantUpdate.getAddressLine1() != null) {
                merchant.setAddressLine1(merchantUpdate.getAddressLine1());
            }

            if (merchantUpdate.getAddressLine2() != null) {
                merchant.setAddressLine2(merchantUpdate.getAddressLine2());
            }

            if (merchantUpdate.getAddressPhoneNumber() != null) {
                merchant.setAddressPhoneNumber(merchantUpdate.getAddressPhoneNumber());
            }

            if (merchantUpdate.getVipLevel() != null) {
                merchant.setVipLevel(merchantUpdate.getVipLevel());
            }

            if (merchantUpdate.getUserLocalTimeZone() != null) {
                merchant.setUserLocalTimeZone(merchantUpdate.getUserLocalTimeZone());
            }

            if (merchantUpdate.getPayerName() != null) {
                merchant.setPayerName(merchantUpdate.getPayerName());
            }

            if (merchantUpdate.getPayerEmail() != null) {
                merchant.setPayerEmail(merchantUpdate.getPayerEmail());
            }

            if (merchantUpdate.getPayerPhoneNumber() != null) {
                merchant.setPayerPhoneNumber(merchantUpdate.getPayerPhoneNumber());
            }

            if (merchantUpdate.getStatus() != null) {
                merchant.setStatus(merchantUpdate.getStatus());
            }

            // 儲存更新
            Merchant updated = merchantRepository.save(merchant);
            log.info("Updated merchant: {}", id);

            return ResponseEntity.ok(AdminApiResponse.success(updated));
        } catch (Exception e) {
            log.error("Error updating merchant {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(AdminApiResponse.serverError("Failed to update merchant: " + e.getMessage()));
        }
    }

    /**
     * DELETE /api/admin/merchant/{id}
     * 刪除商家
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<AdminApiResponse<Void>> deleteMerchant(@PathVariable String id) {
        try {
            Optional<Merchant> existing = merchantRepository.findById(id);
            if (!existing.isPresent()) {
                log.warn("Merchant not found for deletion: {}", id);
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(AdminApiResponse.notFound("Merchant not found: " + id));
            }

            merchantRepository.deleteById(id);
            log.info("Deleted merchant: {}", id);

            return ResponseEntity.ok(AdminApiResponse.success());
        } catch (Exception e) {
            log.error("Error deleting merchant {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(AdminApiResponse.serverError("Failed to delete merchant: " + e.getMessage()));
        }
    }
}
