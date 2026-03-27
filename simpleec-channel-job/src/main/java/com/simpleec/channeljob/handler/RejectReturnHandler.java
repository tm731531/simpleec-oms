package com.simpleec.channeljob.handler;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * REJECT_RETURN handler — notifies the platform that a return/refund has been rejected.
 *
 * Current status: stub. Logs receipt only.
 *
 * TODO: For each platform, call the corresponding return-rejection API:
 *   - Shopee:    POST /api/v2/returns/reject_return
 *   - Cyberbiz:  PUT  /v1/orders/{id}/refund  (status=rejected)
 *   - Easystore: POST /orders/{id}/refunds  (with rejection reason)
 *   - Momo/Yahoo/PChome: TBD per platform contract
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RejectReturnHandler {

    public void handleRejectReturn(String platformCode, String channelId,
                                   String merchantId, JsonNode body) {
        String channelOrderId = body.path("channelOrderId").asText("unknown");
        String returnId = body.path("returnId").asText("unknown");
        String reason = body.path("reason").asText("");

        log.warn("REJECT_RETURN not yet implemented: platform={}, channel={}, orderId={}, returnId={}, reason={}",
                platformCode, channelId, channelOrderId, returnId, reason);

        // TODO: resolve platform adapter and call return-rejection API
    }
}
