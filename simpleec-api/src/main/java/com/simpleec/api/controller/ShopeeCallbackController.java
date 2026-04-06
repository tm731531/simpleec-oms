package com.simpleec.api.controller;

import com.simpleec.api.service.ShopeeOAuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Shopee OAuth 回調端點（公開，不需 JWT）
 *
 * 流程：
 * 1. 蝦皮授權後重導向至此
 * 2. 驗證 state、用 code 換 token、存入 channel
 * 3. 回傳 HTML — postMessage 通知父視窗後關閉小視窗
 *    若無父視窗（直接瀏覽），redirect 到前端 channel 頁面
 *
 * 對應 SecurityConfig.permitAll: /callback/shopee
 */
@Slf4j
@RestController
@RequestMapping("/callback/shopee")
@RequiredArgsConstructor
public class ShopeeCallbackController {

    private final ShopeeOAuthService shopeeOAuthService;

    @GetMapping(produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> handleCallback(
        @RequestParam String code,
        @RequestParam("shop_id") String shopId,
        @RequestParam String state
    ) {
        try {
            String channelId = shopeeOAuthService.handleCallback(code, shopId, state);
            log.info("Shopee OAuth callback success: channelId={}", channelId);

            String html = """
                <!DOCTYPE html>
                <html>
                <head><meta charset="UTF-8"><title>授權成功</title></head>
                <body>
                <p>授權成功，正在關閉視窗...</p>
                <script>
                  if (window.opener) {
                    window.opener.postMessage(
                      { type: 'shopee_oauth_success', channelId: '%s' },
                      '*'
                    );
                    window.close();
                  } else {
                    window.location.href = '/channels/%s?oauth=success';
                  }
                </script>
                </body>
                </html>
                """.formatted(channelId, channelId);

            return ResponseEntity.ok(html);

        } catch (Exception e) {
            log.error("Shopee OAuth callback failed", e);

            String html = """
                <!DOCTYPE html>
                <html>
                <head><meta charset="UTF-8"><title>授權失敗</title></head>
                <body>
                <p>授權失敗：%s</p>
                <script>
                  if (window.opener) {
                    window.opener.postMessage(
                      { type: 'shopee_oauth_error', message: '%s' },
                      '*'
                    );
                    window.close();
                  } else {
                    window.location.href = '/channels?oauth=error';
                  }
                </script>
                </body>
                </html>
                """.formatted(e.getMessage(), e.getMessage());

            return ResponseEntity.badRequest().body(html);
        }
    }
}
