package com.simpleec.api.controller;

import com.simpleec.api.vo.StatusOptionVO;
import com.simpleec.common.enums.OrderStatusEnum;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 枚舉控制器 - 提供系統內的常數值和選項列表
 *
 * 公開端點，無需身份驗證
 */
@Slf4j
@RestController
@RequestMapping("/api/enums")
public class EnumController {

    private final ObjectMapper objectMapper;

    public EnumController(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * GET /api/enums/order-statuses
     *
     * 返回所有訂單狀態選項
     *
     * @return 包含所有訂單狀態的 StatusOptionVO 列表
     */
    @GetMapping("/order-statuses")
    public ResponseEntity<Object> getOrderStatuses() {
        log.info("Fetching order status enums");

        // 將 OrderStatusEnum 所有值轉換為 StatusOptionVO
        List<StatusOptionVO> statusOptions = Arrays.stream(OrderStatusEnum.values())
            .map(status -> new StatusOptionVO(
                status.getCode(),
                status.getLabel(),
                status.getDescription()
            ))
            .collect(Collectors.toList());

        // 構建響應格式
        ArrayNode dataArray = objectMapper.createArrayNode();
        for (StatusOptionVO option : statusOptions) {
            ObjectNode optionNode = objectMapper.createObjectNode();
            optionNode.put("code", option.getCode());
            optionNode.put("label", option.getLabel());
            optionNode.put("description", option.getDescription());
            dataArray.add(optionNode);
        }

        ObjectNode response = objectMapper.createObjectNode();
        response.set("data", dataArray);

        log.info("Successfully fetched {} order status options", statusOptions.size());
        return ResponseEntity.ok(response);
    }
}
