package com.simpleec.channeljob.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.simpleec.channel.adapter.ChannelAdapter;
import com.simpleec.channel.adapter.CyberbizAdapter;
import com.simpleec.channeljob.entity.Channel;
import com.simpleec.channeljob.service.ChannelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * REJECT_RETURN handler — notifies the platform that a return has been rejected.
 *
 * Cyberbiz: PUT /v1/orders/{order_id}/manual_return  operation=manual_return_refuse
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RejectReturnHandler {

    private final ChannelService channelService;
    private final ChannelAdapter cyberbizAdapter;

    public void handleRejectReturn(String platformCode, String channelId,
                                   String merchantId, JsonNode body) {
        String channelOrderId = body.path("channelOrderId").asText("unknown");
        String returnId       = body.path("returnId").asText("unknown");
        String reason         = body.path("reason").asText("");

        if ("cyberbiz".equalsIgnoreCase(platformCode)) {
            Channel channel = channelService.getChannel(channelId);
            if (channel == null) {
                log.error("REJECT_RETURN: channel not found: {}", channelId);
                return;
            }
            try {
                CyberbizAdapter adapter = (CyberbizAdapter) cyberbizAdapter;
                adapter.setCredentials(channel.getToken(), channel.getToken2());
                adapter.rejectReturn(channelOrderId);
                log.info("REJECT_RETURN success: platform={} channel={} orderId={} returnId={} reason={}",
                        platformCode, channelId, channelOrderId, returnId, reason);
            } catch (Exception e) {
                log.error("REJECT_RETURN failed: platform={} channel={} orderId={} returnId={}",
                        platformCode, channelId, channelOrderId, returnId, e);
                throw new RuntimeException("REJECT_RETURN failed for orderId=" + channelOrderId, e);
            }
        } else {
            log.warn("REJECT_RETURN not yet implemented: platform={}, channel={}, orderId={}, returnId={}, reason={}",
                    platformCode, channelId, channelOrderId, returnId, reason);
        }
    }
}
