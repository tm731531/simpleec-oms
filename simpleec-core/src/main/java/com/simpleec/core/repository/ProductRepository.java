package com.simpleec.core.repository;

import com.simpleec.core.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 商品倉庫
 */
@Repository
public interface ProductRepository extends JpaRepository<Product, String> {

    /**
     * 根據 SKU 查詢（商家內唯一）
     */
    Optional<Product> findByMerchantIdAndSku(String merchantId, String sku);

    /**
     * 查詢商家的所有商品（分頁）
     */
    Page<Product> findByMerchantId(String merchantId, Pageable pageable);

    /**
     * 查詢商家的所有商品（按狀態篩選）
     */
    Page<Product> findByMerchantIdAndStatus(String merchantId, String status, Pageable pageable);

    /**
     * 按 SKU 模糊搜尋
     */
    Page<Product> findByMerchantIdAndSkuContaining(String merchantId, String skuPattern, Pageable pageable);

    /**
     * 查詢庫存低於安全值的商品
     */
    Page<Product> findByMerchantIdAndQuantityLessThan(String merchantId, Integer safetyQuantity, Pageable pageable);

    /**
     * Find products where quantity <= safety_quantity (low-stock alert).
     * Uses a JPQL column-vs-column comparison which Spring Data derived queries cannot express.
     */
    @Query("SELECT p FROM Product p WHERE p.merchantId = :merchantId AND p.quantity <= p.safetyQuantity")
    Page<Product> findLowStockByMerchantId(@Param("merchantId") String merchantId, Pageable pageable);
}
