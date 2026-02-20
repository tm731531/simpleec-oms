package com.simpleec.core.repository;

import com.simpleec.core.entity.Account;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 帳號資料庫訪問層
 */
@Repository
public interface AccountRepository extends JpaRepository<Account, String> {

    /**
     * 根據帳號信箱查詢
     */
    Optional<Account> findByAccountEmail(String accountEmail);

    /**
     * 根據商家 ID 分頁查詢所有帳號
     */
    Page<Account> findByMerchantId(String merchantId, Pageable pageable);

    /**
     * 根據商家 ID 和狀態分頁查詢
     */
    Page<Account> findByMerchantIdAndStatus(String merchantId, String status, Pageable pageable);

    /**
     * 根據商家 ID 搜尋帳號（名稱或信箱）
     */
    @Query("SELECT a FROM Account a WHERE a.merchantId = :merchantId AND (a.accountName LIKE %:keyword% OR a.accountEmail LIKE %:keyword%)")
    Page<Account> searchByMerchantIdAndKeyword(@Param("merchantId") String merchantId, @Param("keyword") String keyword, Pageable pageable);

    /**
     * 根據商家 ID 搜尋帳號並過濾狀態
     */
    @Query("SELECT a FROM Account a WHERE a.merchantId = :merchantId AND (a.accountName LIKE %:keyword% OR a.accountEmail LIKE %:keyword%) AND a.status = :status")
    Page<Account> searchByMerchantIdKeywordAndStatus(@Param("merchantId") String merchantId, @Param("keyword") String keyword, @Param("status") String status, Pageable pageable);

    /**
     * 查詢商家是否有主帳號
     */
    boolean existsByMerchantIdAndIsMainAccountTrue(String merchantId);
}
