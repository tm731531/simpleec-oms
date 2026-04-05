package com.simpleec.core.repository;

import com.simpleec.core.entity.SellPackInventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface SellPackInventoryRepository extends JpaRepository<SellPackInventory, String> {

    List<SellPackInventory> findBySellPackId(String sellPackId);

    /** 無 location 概念的平台（Shopee、Cyberbiz 等）*/
    Optional<SellPackInventory> findBySellPackIdAndChannelLocationIdIsNull(String sellPackId);

    /** 有 location 概念的平台（Shopify），查特定 location */
    Optional<SellPackInventory> findBySellPackIdAndChannelLocationId(String sellPackId, String channelLocationId);
}
