package com.simpleec.core.repository;

import com.simpleec.core.entity.SellPack;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

/**
 * 商品上架倉庫
 */
@Repository
public interface SellPackRepository extends JpaRepository<SellPack, String> {

    /**
     * 查詢商家的上架商品（分頁）
     */
    Page<SellPack> findByMerchantId(String merchantId, Pageable pageable);

    /**
     * 查詢商家特定商品的上架資料（分頁）
     */
    Page<SellPack> findByMerchantIdAndProductId(String merchantId, String productId, Pageable pageable);

    /**
     * 查詢商家的特定上架商品
     */
    Optional<SellPack> findByIdAndMerchantId(String id, String merchantId);
}
