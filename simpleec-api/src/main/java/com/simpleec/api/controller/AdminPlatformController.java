package com.simpleec.api.controller;

import com.simpleec.api.dto.AdminApiResponse;
import com.simpleec.api.dto.PageResponse;
import com.simpleec.core.entity.Platform;
import com.simpleec.core.repository.PlatformRepository;
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

/**
 * 管理後台 - 銷售平台管理 API 控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/platform")
@RequiredArgsConstructor
@CrossOrigin(origins = {"http://localhost:8080", "http://localhost:8081", "http://localhost:8082",
                        "http://192.168.0.48:8080", "https://oms.tomting.com", "http://oms.tomting.com"},
             methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.OPTIONS},
             allowedHeaders = {"Content-Type", "Authorization"})
public class AdminPlatformController {

    private final PlatformRepository platformRepository;
    private static final int MAX_PAGE_SIZE = 100;

    /**
     * GET /api/admin/platform
     * 查詢平台列表（分頁）
     *
     * @param page 頁碼（1-based）
     * @param pageSize 每頁記錄數（預設 20，最大 100）
     * @param search 搜尋關鍵字（平台名稱或 Topic）
     * @param actived 篩選啟用狀態（可選）
     * @param sortBy 排序欄位（created_at, platform_name 等）
     * @param order 排序順序（asc 或 desc）
     */
    @GetMapping
    public ResponseEntity<AdminApiResponse<PageResponse<Platform>>> listPlatforms(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer pageSize,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Boolean actived) {

        try {
            // 驗證分頁參數
            if (page < 1) page = 1;
            if (pageSize < 1) pageSize = 1;
            if (pageSize > MAX_PAGE_SIZE) pageSize = MAX_PAGE_SIZE;

            // 使用簡單分頁，不使用排序以避免欄位對應問題
            Pageable pageable = PageRequest.of(page - 1, pageSize);

            // 查詢資料
            Page<Platform> result;
            if (search != null && !search.trim().isEmpty()) {
                if (actived != null) {
                    result = platformRepository.searchByKeywordAndActived(search.trim(), actived, pageable);
                    log.info("Searched platforms with keyword: {}, actived: {}, found {} records", search, actived, result.getTotalElements());
                } else {
                    result = platformRepository.searchByKeyword(search.trim(), pageable);
                    log.info("Searched platforms with keyword: {}, found {} records", search, result.getTotalElements());
                }
            } else if (actived != null) {
                result = platformRepository.findByActived(actived, pageable);
                log.info("Listed platforms by actived: {}, found {} records", actived, result.getTotalElements());
            } else {
                result = platformRepository.findAll(pageable);
                log.info("Listed all platforms, page: {}, size: {}, total: {}", page, pageSize, result.getTotalElements());
            }

            return ResponseEntity.ok(AdminApiResponse.success(PageResponse.fromPage(result)));
        } catch (Exception e) {
            log.error("Error listing platforms", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(AdminApiResponse.serverError("Failed to list platforms: " + e.getMessage()));
        }
    }

    /**
     * GET /api/admin/platform/{id}
     * 查詢單筆平台詳細資訊
     */
    @GetMapping("/{id}")
    public ResponseEntity<AdminApiResponse<Platform>> getPlatform(@PathVariable String id) {
        try {
            Optional<Platform> platform = platformRepository.findById(id);
            if (platform.isPresent()) {
                log.info("Retrieved platform: {}", id);
                return ResponseEntity.ok(AdminApiResponse.success(platform.get()));
            } else {
                log.warn("Platform not found: {}", id);
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(AdminApiResponse.notFound("Platform not found: " + id));
            }
        } catch (Exception e) {
            log.error("Error retrieving platform {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(AdminApiResponse.serverError("Failed to retrieve platform: " + e.getMessage()));
        }
    }

    /**
     * POST /api/admin/platform
     * 建立新平台
     */
    @PostMapping
    public ResponseEntity<AdminApiResponse<Platform>> createPlatform(@RequestBody Platform platform) {
        try {
            // 驗證必填欄位
            if (platform.getPlatformName() == null || platform.getPlatformName().trim().isEmpty()) {
                log.warn("Invalid platform: missing platform_name");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(AdminApiResponse.badRequest("Platform name is required"));
            }

            // 檢查平台名稱是否已存在
            if (platformRepository.findByPlatformName(platform.getPlatformName()).isPresent()) {
                log.warn("Duplicate platform name: {}", platform.getPlatformName());
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(AdminApiResponse.conflict("Platform name already exists: " + platform.getPlatformName()));
            }

            // 設定預設值
            if (platform.getActived() == null) {
                platform.setActived(true);
            }

            if (platform.getCurrency() == null) {
                platform.setCurrency("TWD");
            }

            // 生成 ID
            platform.setId(NanoIdUtil.generate());

            // 儲存平台
            Platform created = platformRepository.save(platform);
            log.info("Created platform: {} ({})", created.getId(), created.getPlatformName());

            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(AdminApiResponse.success(created));
        } catch (Exception e) {
            log.error("Error creating platform", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(AdminApiResponse.serverError("Failed to create platform: " + e.getMessage()));
        }
    }

    /**
     * PUT /api/admin/platform/{id}
     * 更新平台資訊
     */
    @PutMapping("/{id}")
    public ResponseEntity<AdminApiResponse<Platform>> updatePlatform(
            @PathVariable String id,
            @RequestBody Platform platformUpdate) {

        try {
            Optional<Platform> existing = platformRepository.findById(id);
            if (!existing.isPresent()) {
                log.warn("Platform not found for update: {}", id);
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(AdminApiResponse.notFound("Platform not found: " + id));
            }

            Platform platform = existing.get();

            // 更新允許修改的欄位
            if (platformUpdate.getPlatformName() != null && !platformUpdate.getPlatformName().trim().isEmpty()) {
                String newName = platformUpdate.getPlatformName();
                // 檢查新平台名稱是否已被他人使用
                Optional<Platform> existingName = platformRepository.findByPlatformName(newName);
                if (existingName.isPresent() && !existingName.get().getId().equals(id)) {
                    log.warn("Duplicate platform name: {}", newName);
                    return ResponseEntity.status(HttpStatus.CONFLICT)
                            .body(AdminApiResponse.conflict("Platform name already exists: " + newName));
                }
                platform.setPlatformName(newName);
            }

            if (platformUpdate.getCredential1() != null) {
                platform.setCredential1(platformUpdate.getCredential1());
            }

            if (platformUpdate.getCredential2() != null) {
                platform.setCredential2(platformUpdate.getCredential2());
            }

            if (platformUpdate.getActived() != null) {
                platform.setActived(platformUpdate.getActived());
            }

            if (platformUpdate.getQueueTopic() != null) {
                platform.setQueueTopic(platformUpdate.getQueueTopic());
            }

            if (platformUpdate.getCurrency() != null) {
                platform.setCurrency(platformUpdate.getCurrency());
            }

            if (platformUpdate.getShipOptions() != null) {
                platform.setShipOptions(platformUpdate.getShipOptions());
            }

            if (platformUpdate.getCapabilities() != null) {
                platform.setCapabilities(platformUpdate.getCapabilities());
            }

            // 儲存更新
            Platform updated = platformRepository.save(platform);
            log.info("Updated platform: {} ({})", id, updated.getPlatformName());
            log.debug("Updated capabilities: {}", updated.getCapabilities());

            return ResponseEntity.ok(AdminApiResponse.success(updated));
        } catch (Exception e) {
            log.error("Error updating platform {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(AdminApiResponse.serverError("Failed to update platform: " + e.getMessage()));
        }
    }

    /**
     * DELETE /api/admin/platform/{id}
     * 刪除平台
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<AdminApiResponse<Void>> deletePlatform(@PathVariable String id) {
        try {
            Optional<Platform> existing = platformRepository.findById(id);
            if (!existing.isPresent()) {
                log.warn("Platform not found for deletion: {}", id);
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(AdminApiResponse.notFound("Platform not found: " + id));
            }

            platformRepository.deleteById(id);
            log.info("Deleted platform: {}", id);

            return ResponseEntity.ok(AdminApiResponse.success());
        } catch (Exception e) {
            log.error("Error deleting platform {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(AdminApiResponse.serverError("Failed to delete platform: " + e.getMessage()));
        }
    }
}
