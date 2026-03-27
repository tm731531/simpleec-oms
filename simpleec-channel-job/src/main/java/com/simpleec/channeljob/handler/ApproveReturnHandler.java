package com.simpleec.channeljob.handler;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * APPROVE_RETURN handler — notifies the platform that a return/refund has been approved.
 *
 * Current status: stub. Logs receipt only.
 *
 * TODO: For each platform, call the corresponding return-approval API:
 *   - Shopee:    POST /api/v2/returns/confirm_return
 *   - Cyberbiz:  PUT  /v1/orders/{id}/refund  (status=approved)
 *   - Easystore: POST /orders/{id}/refunds
 *   - Momo/Yahoo/PChome: TBD per platform contract
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApproveReturnHandler {

    public void handleApproveReturn(String platformCode, String channelId,
                                    String merchantId, JsonNode body) {
        String channelOrderId = body.path("channelOrderId").asText("unknown");
        String returnId = body.path("returnId").asText("unknown");

        log.warn("APPROVE_RETURN not yet implemented: platform={}, channel={}, orderId={}, returnId={}",
                platformCode, channelId, channelOrderId, returnId);

        // TODO: resolve platform adapter and call return-approval API
    }
}
