package com.oneec.channel.momo;

import com.oneec.channel.adapter.ChannelAdapter;
import com.oneec.common.enums.ChannelType;
import com.oneec.core.entity.Order;
import com.oneec.core.entity.SellPack;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * momo 購物通路 Adapter
 * API 文件: momo 店+ vendor API (SCM API v5.1)
 * TODO: 實作各 API 呼叫
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MomoChannelAdapter implements ChannelAdapter {

    @Override
    public ChannelType getChannelType() {
        return ChannelType.MOMO;
    }

    @Override
    public boolean validateConnection(Map<String, String> credentials) {
        // TODO: call momo Token API to verify
        log.info("[momo] validateConnection called");
        return true;
    }

    @Override
    public String createListing(Long channelId, SellPack sellPack, Map<String, Object> extraData) {
        // TODO: call momo GoodsStartSelling API
        log.info("[momo] createListing for channelId={}, product={}", channelId, sellPack.getTitle());
        return "MOMO_PRODUCT_ID_PLACEHOLDER";
    }

    @Override
    public void updateListing(Long channelId, SellPack sellPack, Map<String, Object> extraData) {
        log.info("[momo] updateListing for channelId={}", channelId);
    }

    @Override
    public void updatePrice(Long channelId, String channelProductId, BigDecimal price) {
        // TODO: call momo GoodsPriceModify API
        log.info("[momo] updatePrice channelProductId={}, price={}", channelProductId, price);
    }

    @Override
    public void updateQuantity(Long channelId, String channelProductId, int quantity) {
        log.info("[momo] updateQuantity channelProductId={}, qty={}", channelProductId, quantity);
    }

    @Override
    public void startSelling(Long channelId, String channelProductId) {
        // TODO: call momo GoodsStartSelling API
        log.info("[momo] startSelling channelProductId={}", channelProductId);
    }

    @Override
    public void stopSelling(Long channelId, String channelProductId) {
        // TODO: call momo GoodsStopSelling API
        log.info("[momo] stopSelling channelProductId={}", channelProductId);
    }

    @Override
    public List<Order> fetchOrders(Long channelId, LocalDateTime from, LocalDateTime to) {
        // TODO: call momo order query API
        log.info("[momo] fetchOrders channelId={}, from={}, to={}", channelId, from, to);
        return List.of();
    }

    @Override
    public void confirmShipment(Long channelId, String channelOrderId, String trackingNumber, String logisticsCompany) {
        log.info("[momo] confirmShipment orderId={}, tracking={}", channelOrderId, trackingNumber);
    }

    @Override
    public void acceptCancellation(Long channelId, String channelOrderId) {
        log.info("[momo] acceptCancellation orderId={}", channelOrderId);
    }

    @Override
    public String getShippingLabel(Long channelId, String channelOrderId) {
        log.info("[momo] getShippingLabel orderId={}", channelOrderId);
        return null;
    }
}
