package com.simpleec.core.service;

import com.simpleec.core.entity.Product;
import com.simpleec.core.repository.ProductRepository;
import com.simpleec.common.util.NanoIdUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 商品服務
 *
 * 只在 ProductRepository bean 存在時才創建此服務（即有數據庫配置時）
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnBean(ProductRepository.class)
public class ProductService {

    private final ProductRepository productRepository;

    /**
     * 查詢單筆商品
     */
    public Optional<Product> findById(String productId) {
        return productRepository.findById(productId);
    }

    /**
     * 根據 SKU 查詢
     */
    public Optional<Product> findBySku(String merchantId, String sku) {
        return productRepository.findByMerchantIdAndSku(merchantId, sku);
    }

    /**
     * 查詢商家的所有商品（分頁）
     */
    public Page<Product> findByMerchantId(String merchantId, Pageable pageable) {
        return productRepository.findByMerchantId(merchantId, pageable);
    }

    /**
     * 查詢商家的商品（按狀態篩選）
     */
    public Page<Product> findByStatus(String merchantId, String status, Pageable pageable) {
        return productRepository.findByMerchantIdAndStatus(merchantId, status, pageable);
    }

    /**
     * 搜尋商品（SKU 模糊搜尋）
     */
    public Page<Product> searchBySku(String merchantId, String skuPattern, Pageable pageable) {
        return productRepository.findByMerchantIdAndSkuContaining(merchantId, skuPattern, pageable);
    }

    /**
     * 查詢庫存不足的商品
     */
    public Page<Product> findLowStock(String merchantId, Pageable pageable) {
        return productRepository.findByMerchantIdAndQuantityLessThan(merchantId, 10, pageable);
    }

    /**
     * 建立商品
     */
    @Transactional
    public Product createProduct(Product product) {
        if (product.getId() == null) {
            product.setId(NanoIdUtil.generateWithPrefix("PROD_"));
        }

        log.info("Creating product: {} (SKU: {})", product.getId(), product.getSku());
        return productRepository.save(product);
    }

    /**
     * 更新商品
     */
    @Transactional
    public Product updateProduct(Product product) {
        log.info("Updating product: {}", product.getId());
        return productRepository.save(product);
    }

    /**
     * 更新庫存
     */
    @Transactional
    public void updateQuantity(String productId, int quantity) {
        Optional<Product> product = findById(productId);
        if (product.isPresent()) {
            Product p = product.get();
            p.setQuantity(quantity);
            productRepository.save(p);
            log.info("Updated quantity for product {} to {}", productId, quantity);
        }
    }
}
