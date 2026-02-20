package com.simpleec.core.repository;

import com.simpleec.core.entity.Platform;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 銷售平台資料庫訪問層
 */
@Repository
public interface PlatformRepository extends JpaRepository<Platform, String> {

    /**
     * 根據平台名稱查詢
     */
    Optional<Platform> findByPlatformName(String platformName);

    /**
     * 查詢所有啟用的平台
     */
    Page<Platform> findByActived(Boolean actived, Pageable pageable);

    /**
     * 根據平台名稱搜尋（模糊查詢）
     */
    @Query("SELECT p FROM Platform p WHERE p.platformName LIKE %:keyword% OR p.queueTopic LIKE %:keyword%")
    Page<Platform> searchByKeyword(@Param("keyword") String keyword, Pageable pageable);

    /**
     * 根據平台名稱搜尋並過濾啟用狀態
     */
    @Query("SELECT p FROM Platform p WHERE (p.platformName LIKE %:keyword% OR p.queueTopic LIKE %:keyword%) AND p.actived = :actived")
    Page<Platform> searchByKeywordAndActived(@Param("keyword") String keyword, @Param("actived") Boolean actived, Pageable pageable);
}
