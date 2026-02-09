package com.simpleec.api.controller;

import com.simpleec.api.vo.OrderVO;
import com.simpleec.common.model.ApiResponse;
import com.simpleec.common.model.PageResult;
import com.simpleec.core.entity.Order;
import com.simpleec.core.service.OrderService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @GetMapping
    public ApiResponse<PageResult<OrderVO>> list(
            @RequestParam String merchantId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageResult<Order> result = orderService.list(merchantId, page, size);
        List<OrderVO> masked = result.getRecords().stream()
                .map(OrderVO::fromMasked)
                .toList();
        return ApiResponse.ok(PageResult.of(masked, result.getTotal(), page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<OrderVO> getById(
            @PathVariable String id,
            @RequestParam String merchantId) {
        Order order = orderService.getById(merchantId, id);
        return ApiResponse.ok(OrderVO.fromPlain(order));
    }

    @GetMapping("/export")
    public void export(
            @RequestParam String merchantId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate,
            HttpServletResponse response) throws IOException {

        List<Order> orders = orderService.listForExport(merchantId, status, startDate, endDate);

        String filename = "orders_" + merchantId + "_"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + ".csv";

        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");

        OutputStream out = response.getOutputStream();
        // UTF-8 BOM for Excel
        out.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});

        writeLine(out, "Order ID,Channel,Channel Order ID,Status,Buyer Name,Buyer Phone,"
                + "Buyer Email,Shipping Address,Shipping Method,Payment Method,"
                + "Total Amount,Shipping Fee,Discount,Order Date");

        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        for (Order order : orders) {
            writeLine(out, String.join(",",
                    csvEscape(order.getId()),
                    csvEscape(order.getChannelId()),
                    csvEscape(order.getChannelOrderId()),
                    csvEscape(order.getOrderStatus()),
                    csvEscape(order.getBuyerName()),
                    csvEscape(order.getBuyerPhone()),
                    csvEscape(order.getBuyerEmail()),
                    csvEscape(order.getShippingAddress()),
                    csvEscape(order.getShippingMethod()),
                    csvEscape(order.getPaymentMethod()),
                    order.getTotalAmount() != null ? order.getTotalAmount().toPlainString() : "",
                    order.getShippingFee() != null ? order.getShippingFee().toPlainString() : "",
                    order.getDiscountAmount() != null ? order.getDiscountAmount().toPlainString() : "",
                    order.getChannelCreatedAt() != null ? order.getChannelCreatedAt().format(dtf) : ""
            ));
        }
        out.flush();
    }

    @PostMapping("/{id}/status")
    public ApiResponse<Void> updateStatus(
            @PathVariable String id,
            @RequestParam String merchantId,
            @RequestParam String toStatus,
            @RequestParam(required = false) String remark) {
        orderService.updateStatus(merchantId, id, null, toStatus, "system", remark);
        return ApiResponse.ok();
    }

    private static void writeLine(OutputStream out, String line) throws IOException {
        out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static String csvEscape(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
