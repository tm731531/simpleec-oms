package com.simpleec.orderjob.handler;

import com.simpleec.core.entity.ReturnOrder;
import com.simpleec.core.service.ReturnOrderService;
import com.simpleec.common.enums.ReturnStatusEnum;
import com.simpleec.common.util.RedisKeyUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ReturnUpsertHandler 單元測試
 *
 * 測試場景：
 * 1. Redis 快速路徑（已處理過的退貨）
 * 2. 資料庫 INSERT（新退貨）
 * 3. 資料庫 UPDATE（現有退貨且有變化）
 */
@ExtendWith(MockitoExtension.class)
class ReturnUpsertHandlerTest {

    @Mock
    private ReturnOrderService returnOrderService;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> redisValueOperations;

    @InjectMocks
    private ReturnUpsertHandler returnUpsertHandler;

    private ObjectMapper objectMapper;
    private String merchantId;
    private String channelId;
    private String channelReturnId;
    private String returnHash;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        merchantId = "MERCH_001";
        channelId = "shopee";
        channelReturnId = "RET_12345";
        returnHash = "abc123def456";

        // Setup Redis mock chain
        when(redisTemplate.opsForValue()).thenReturn(redisValueOperations);
    }

    /**
     * 測試 1: Redis 快速路徑 — hash 匹配，跳過處理
     */
    @Test
    void testHandleReturnUpsert_RedisCacheHit_SkipsProcessing() throws Exception {
        // 準備
        String redisKey = RedisKeyUtil.returnHashKey(merchantId, channelId, channelReturnId);
        when(redisValueOperations.get(redisKey)).thenReturn(returnHash);

        ObjectNode returnData = objectMapper.createObjectNode()
            .put("status", "PENDING")
            .put("refundAmount", "100.00");

        // 執行
        returnUpsertHandler.handleReturnUpsert(
            merchantId, channelId, channelReturnId, returnHash, returnData
        );

        // 驗證：ReturnOrderService 不被呼叫
        verify(returnOrderService, never()).findByChannelIdAndChannelRefundId(any(), any());
        verify(returnOrderService, never()).createReturn(any());
        verify(returnOrderService, never()).updateReturn(any());

        // 驗證：Redis 不被更新（已匹配舊 hash）
        verify(redisValueOperations, never()).set(any(), any(), any());
    }

    /**
     * 測試 2: 資料庫 INSERT — 新退貨不存在於 DB
     */
    @Test
    void testHandleReturnUpsert_DatabaseInsert_CreatesNewReturn() throws Exception {
        // 準備
        String redisKey = RedisKeyUtil.returnHashKey(merchantId, channelId, channelReturnId);
        String orderId = "ORD_001";

        // Redis 中無此 hash（模擬首次處理）
        when(redisValueOperations.get(redisKey)).thenReturn(null);

        // DB 中無此退貨
        when(returnOrderService.findByChannelIdAndChannelRefundId(channelId, channelReturnId)).thenReturn(Optional.empty());

        // 設置 Mock 返回的 ReturnOrder（DB INSERT 後）
        ReturnOrder savedReturn = new ReturnOrder();
        savedReturn.setId("RET_001");
        savedReturn.setMerchantId(merchantId);
        savedReturn.setOrderId(orderId);
        savedReturn.setChannelRefundId(channelReturnId);
        savedReturn.setReturnStatus(ReturnStatusEnum.PENDING);
        savedReturn.setRefundAmount(new BigDecimal("100.00"));
        savedReturn.setCreatedAt(LocalDateTime.now());

        when(returnOrderService.updateReturn(any(ReturnOrder.class))).thenReturn(savedReturn);

        ObjectNode returnData = objectMapper.createObjectNode()
            .put("orderId", orderId)
            .put("status", "PENDING")
            .put("refundAmount", "100.00")
            .put("reason", "不滿意");

        // 執行
        returnUpsertHandler.handleReturnUpsert(
            merchantId, channelId, channelReturnId, returnHash, returnData
        );

        // 驗證：updateReturn 被呼叫（用於 INSERT）
        ArgumentCaptor<ReturnOrder> captor = ArgumentCaptor.forClass(ReturnOrder.class);
        verify(returnOrderService).updateReturn(captor.capture());
        ReturnOrder capturedReturn = captor.getValue();
        assertEquals(channelReturnId, capturedReturn.getChannelRefundId());
        assertEquals(ReturnStatusEnum.PENDING, capturedReturn.getReturnStatus());

        // 驗證：Redis 被更新
        verify(redisValueOperations).set(
            eq(redisKey),
            eq(returnHash),
            eq(Duration.ofHours(24))
        );
    }

    /**
     * 測試 3: 資料庫 UPDATE — 退貨存在且內容有變化
     */
    @Test
    void testHandleReturnUpsert_DatabaseUpdate_UpdatesExistingReturn() throws Exception {
        // 準備
        String redisKey = RedisKeyUtil.returnHashKey(merchantId, channelId, channelReturnId);
        String orderId = "ORD_001";
        String existingReturnId = "RET_001";

        // Redis 中無舊 hash（或不同的舊 hash）
        when(redisValueOperations.get(redisKey)).thenReturn(null);

        // DB 中已存在此退貨
        ReturnOrder existingReturn = new ReturnOrder();
        existingReturn.setId(existingReturnId);
        existingReturn.setMerchantId(merchantId);
        existingReturn.setOrderId(orderId);
        existingReturn.setChannelRefundId(channelReturnId);
        existingReturn.setReturnStatus(ReturnStatusEnum.PENDING);
        existingReturn.setRefundAmount(new BigDecimal("50.00"));  // 舊金額
        existingReturn.setCreatedAt(LocalDateTime.now().minusHours(1));

        when(returnOrderService.findByChannelIdAndChannelRefundId(channelId, channelReturnId))
            .thenReturn(Optional.of(existingReturn));

        // 設置 Mock 返回的更新後 ReturnOrder
        ReturnOrder updatedReturn = new ReturnOrder();
        updatedReturn.setId(existingReturnId);
        updatedReturn.setMerchantId(merchantId);
        updatedReturn.setOrderId(orderId);
        updatedReturn.setChannelRefundId(channelReturnId);
        updatedReturn.setReturnStatus(ReturnStatusEnum.APPROVED);  // 新狀態
        updatedReturn.setRefundAmount(new BigDecimal("100.00"));  // 新金額
        updatedReturn.setUpdatedAt(LocalDateTime.now());

        when(returnOrderService.updateReturn(any(ReturnOrder.class))).thenReturn(updatedReturn);

        ObjectNode returnData = objectMapper.createObjectNode()
            .put("orderId", orderId)
            .put("status", "APPROVED")  // 狀態改變
            .put("refundAmount", "100.00");  // 金額改變

        // 執行
        returnUpsertHandler.handleReturnUpsert(
            merchantId, channelId, channelReturnId, returnHash, returnData
        );

        // 驗證：updateReturn 被呼叫
        ArgumentCaptor<ReturnOrder> captor = ArgumentCaptor.forClass(ReturnOrder.class);
        verify(returnOrderService).updateReturn(captor.capture());
        ReturnOrder capturedReturn = captor.getValue();
        assertEquals(existingReturnId, capturedReturn.getId());
        assertEquals(ReturnStatusEnum.APPROVED, capturedReturn.getReturnStatus());
        assertEquals(new BigDecimal("100.00"), capturedReturn.getRefundAmount());

        // 驗證：Redis 被更新
        verify(redisValueOperations).set(
            eq(redisKey),
            eq(returnHash),
            eq(Duration.ofHours(24))
        );
    }
}
