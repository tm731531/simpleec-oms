package com.simpleec.core.repository;

import com.simpleec.core.entity.Merchant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 商家資料庫訪問層
 */
@Repository
public interface MerchantRepository extends JpaRepository<Merchant, String> {

    /**
     * 根據商家信箱查詢
     */
    Optional<Merchant> findByMerchantEmail(String merchantEmail);

    /**
     * 根據狀態分頁查詢
     */
    Page<Merchant> findByStatus(String status, Pageable pageable);

    /**
     * 根據商家名稱搜尋（模糊查詢）
     */
    @Query("SELECT m FROM Merchant m WHERE m.merchantName LIKE %:keyword% OR m.merchantEmail LIKE %:keyword%")
    Page<Merchant> searchByKeyword(@Param("keyword") String keyword, Pageable pageable);

    /**
     * 根據商家名稱搜尋並過濾狀態
     */
    @Query("SELECT m FROM Merchant m WHERE (m.merchantName LIKE %:keyword% OR m.merchantEmail LIKE %:keyword%) AND m.status = :status")
    Page<Merchant> searchByKeywordAndStatus(@Param("keyword") String keyword, @Param("status") String status, Pageable pageable);
}
