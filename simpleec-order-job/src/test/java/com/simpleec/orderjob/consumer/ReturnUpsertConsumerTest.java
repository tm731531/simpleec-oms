package com.simpleec.orderjob.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simpleec.orderjob.handler.ReturnUpsertHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReturnUpsertConsumerTest {

    @Mock
    private ReturnUpsertHandler returnUpsertHandler;

    @Mock
    private Acknowledgment acknowledgment;

    @InjectMocks
    private ReturnUpsertConsumer consumer;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        consumer = new ReturnUpsertConsumer(returnUpsertHandler, objectMapper);
    }

    @Test
    void testConsumeReturnUpsert_ValidMessage_CallsHandler() throws Exception {
        // Given: Valid RETURN_UPSERT message
        ObjectNode message = createValidReturnMessage();
        String messageStr = objectMapper.writeValueAsString(message);

        // When: Consumer processes message
        consumer.consumeReturnUpsert(messageStr, 0, acknowledgment);

        // Then: Handler should be called and offset acknowledged
        verify(returnUpsertHandler, times(1)).handleReturnUpsert(
            eq("MERCHANT_001"),
            eq("shopee"),
            eq("REF-2024-001"),
            anyString(),
            any()
        );
        verify(acknowledgment, times(1)).acknowledge();
    }

    @Test
    void testConsumeReturnUpsert_InvalidTaskType_SkipsProcessing() throws Exception {
        // Given: Message with wrong taskType
        ObjectNode message = objectMapper.createObjectNode();
        ObjectNode header = objectMapper.createObjectNode();
        header.put("taskType", "INVALID_TYPE");
        header.put("merchantId", "MERCHANT_001");
        message.set("header", header);

        String messageStr = objectMapper.writeValueAsString(message);

        // When: Consumer processes message
        consumer.consumeReturnUpsert(messageStr, 0, acknowledgment);

        // Then: Handler should NOT be called
        verify(returnUpsertHandler, never()).handleReturnUpsert(any(), any(), any(), any(), any());
        verify(acknowledgment, times(1)).acknowledge();
    }

    private ObjectNode createValidReturnMessage() {
        ObjectNode message = objectMapper.createObjectNode();

        ObjectNode header = objectMapper.createObjectNode();
        header.put("messageId", "msg_12345");
        header.put("taskType", "RETURN_UPSERT");
        header.put("merchantId", "MERCHANT_001");
        header.put("channelId", "shopee");
        header.put("timestamp", java.time.Instant.now().toString());
        header.put("version", "1.0");
        message.set("header", header);

        ObjectNode body = objectMapper.createObjectNode();
        body.put("channelRefundId", "REF-2024-001");
        body.put("returnHash", "abc123def456");
        ObjectNode returnData = objectMapper.createObjectNode();
        returnData.put("status", "PENDING");
        returnData.put("reason", "Product defect");
        returnData.put("refund_amount", "1500.00");
        body.set("returnData", returnData);
        message.set("body", body);

        return message;
    }
}
