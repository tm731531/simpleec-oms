package com.simpleec.api.controller;

import com.simpleec.api.dto.UserPageResponse;
import com.simpleec.api.security.UserPrincipal;
import com.simpleec.core.entity.Product;
import com.simpleec.core.repository.ProductRepository;
import com.simpleec.common.util.NanoIdUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.Optional;

/**
 * 用戶商品管理控制器
 */
@Slf4j
@RestController
@RequestMapping("/user/products")
@RequiredArgsConstructor
public class UserProductController {
    private final ProductRepository productRepository;

    @GetMapping
    public ResponseEntity<UserPageResponse<Product>> listProducts(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Page<Product> products = productRepository.findByMerchantId(
            principal.getMerchantId(), PageRequest.of(page, size));
        return ResponseEntity.ok(UserPageResponse.from(products));
    }

    @PostMapping
    public ResponseEntity<Product> createProduct(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody Product product) {
        // Generate Product ID: merchant(4) + yyyyMMddHHmmss(14) + random(2) = 20 chars
        String merchantId = principal.getMerchantId();
        String merchantPrefix = merchantId != null && merchantId.length() >= 4 ? merchantId.substring(0, 4) : "XXXX";
        String timestamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String randomPart = NanoIdUtil.generate().substring(0, 2);
        String productId = merchantPrefix + timestamp + randomPart;
        product.setId(productId);
        product.setMerchantId(principal.getMerchantId());
        if (product.getStatus() == null) product.setStatus("active");
        Product saved = productRepository.save(product);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Product> getProduct(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String id) {
        Optional<Product> product = productRepository.findById(id);
        if (product.isEmpty() || !principal.getMerchantId().equals(product.get().getMerchantId())) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(product.get());
    }

    @PatchMapping("/{id}")
    public ResponseEntity<Product> updateProduct(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String id,
            @RequestBody Map<String, Object> updates) {
        Optional<Product> productOpt = productRepository.findById(id);
        if (productOpt.isEmpty() || !principal.getMerchantId().equals(productOpt.get().getMerchantId())) {
            return ResponseEntity.notFound().build();
        }
        Product product = productOpt.get();
        if (updates.containsKey("productName")) product.setProductName((String) updates.get("productName"));
        if (updates.containsKey("sku")) product.setSku((String) updates.get("sku"));
        if (updates.containsKey("status")) product.setStatus((String) updates.get("status"));
        if (updates.containsKey("quantity")) product.setQuantity((Integer) updates.get("quantity"));
        return ResponseEntity.ok(productRepository.save(product));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteProduct(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String id) {
        Optional<Product> product = productRepository.findById(id);
        if (product.isEmpty() || !principal.getMerchantId().equals(product.get().getMerchantId())) {
            return ResponseEntity.notFound().build();
        }
        productRepository.delete(product.get());
        return ResponseEntity.noContent().build();
    }
}
