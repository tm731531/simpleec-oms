package com.simpleec.core.repository;

import com.simpleec.core.entity.Channel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

/**
 * 通路倉庫
 */
@Repository
public interface ChannelRepository extends JpaRepository<Channel, String> {

    /**
     * 查詢商家的已啟用通路
     */
    List<Channel> findByMerchantIdAndActivedTrue(String merchantId);

    /**
     * 查詢商家的所有通路
     */
    List<Channel> findByMerchantId(String merchantId);

    /**
     * 查詢已啟用且有同步權限的通路（用於排程器派發任務前驗證）
     */
    List<Channel> findByActivedTrueAndEnableSyncTrue();

    /**
     * 根據平台ID和同步狀態查詢通路
     */
    List<Channel> findByPlatformIdAndActivedTrueAndEnableSyncTrue(String platformId);

    /**
     * 查詢指定平台的所有啟用通路（用於 token 刷新掃描）
     */
    List<Channel> findByPlatformIdAndActivedTrue(String platformId);

    /**
     * 查詢所有不同的商家 ID（已啟用通路）
     */
    @org.springframework.data.jpa.repository.Query("SELECT DISTINCT c.merchantId FROM Channel c WHERE c.actived = true")
    List<String> findDistinctMerchantIdsByActivedTrue();
}
