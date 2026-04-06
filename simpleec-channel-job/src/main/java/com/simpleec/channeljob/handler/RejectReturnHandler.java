package com.simpleec.channeljob.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.simpleec.channel.adapter.ChannelAdapter;
import com.simpleec.channel.registry.ChannelAdapterRegistry;
import com.simpleec.channeljob.entity.Channel;
import com.simpleec.channeljob.entity.OrderRef;
import com.simpleec.channeljob.entity.ReturnOrderRef;
import com.simpleec.channeljob.repository.OrderRefRepository;
import com.simpleec.channeljob.repository.ReturnOrderRefRepository;
import com.simpleec.channeljob.service.ChannelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * REJECT_RETURN handler — notifies the platform that a return has been rejected.
 *
 * Translation layer: reads returnId (OMS NanoID) from body, looks up channel_order_id via
 * refund_orders → orders.
 *
 * Cyberbiz: PUT /v1/orders/{order_id}/manual_return  operation=manual_return_refuse
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RejectReturnHandler {

    private final ChannelService channelService;
    private final ChannelAdapterRegistry adapterRegistry;
    private final OrderRefRepository orderRefRepository;
    private final ReturnOrderRefRepository returnOrderRefRepository;

    public void handleRejectReturn(String platformCode, String channelId,
                                   String merchantId, JsonNode body) {
        String returnId = body.path("returnId").asText("unknown");
        String reason   = body.path("reason").asText("");

        // Translation: returnId → orderId → channelOrderId
        ReturnOrderRef returnRef = returnOrderRefRepository.findById(returnId).orElse(null);
        if (returnRef == null) {
            log.error("REJECT_RETURN: return order not found in DB: {}", returnId);
            return;
        }
        OrderRef orderRef = orderRefRepository.findById(returnRef.getOrderId()).orElse(null);
        if (orderRef == null) {
            log.error("REJECT_RETURN: parent order not found in DB: {}", returnRef.getOrderId());
            return;
        }
        String channelOrderId = orderRef.getChannelOrderId();

        JsonNode capabilities = channelService.getPlatformCapabilities(channelId);
        if (capabilities.path("supportsReturnApproval").asBoolean(false)) {
            Channel channel = channelService.getChannel(channelId);
            if (channel == null) {
                log.error("REJECT_RETURN: channel not found: {}", channelId);
                return;
            }
            try {
                ChannelAdapter adapter = adapterRegistry.getAdapter(platformCode);
                adapter.setCredentials(channelId, channel.getToken(), channel.getToken2());
                adapter.rejectReturn(channelOrderId);
                log.info("REJECT_RETURN success: platform={} channel={} orderId={} returnId={} reason={}",
                        platformCode, channelId, channelOrderId, returnId, reason);
            } catch (Exception e) {
                log.error("REJECT_RETURN failed: platform={} channel={} orderId={} returnId={}",
                        platformCode, channelId, channelOrderId, returnId, e);
                throw new RuntimeException("REJECT_RETURN failed for orderId=" + channelOrderId, e);
            }
        } else {
            log.warn("REJECT_RETURN not supported for channel={} (supportsReturnApproval=false): orderId={}, returnId={}, reason={}",
                    channelId, channelOrderId, returnId, reason);
        }
    }
}
