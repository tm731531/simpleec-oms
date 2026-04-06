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
                  (function() {
                    var channelId = '%s';
                    if (window.opener && !window.opener.closed) {
                      try {
                        window.opener.postMessage(
                          { type: 'shopee_oauth_success', channelId: channelId },
                          window.location.origin
                        );
                      } catch (e) {
                        // cross-origin fallback: use '*' if same-origin fails
                        window.opener.postMessage(
                          { type: 'shopee_oauth_success', channelId: channelId },
                          '*'
                        );
                      }
                      setTimeout(function() { window.close(); }, 500);
                    } else {
                      window.location.replace('/channels/' + channelId + '?oauth=success');
                    }
                  })();
                </script>
                </body>
                </html>
                """.formatted(channelId);

            return ResponseEntity.ok(html);

        } catch (Exception e) {
            log.error("Shopee OAuth callback failed", e);

            String safeMsg = e.getMessage() == null ? "Unknown error" : e.getMessage()
                .replace("'", "\\'").replace("\n", " ");
            String html = """
                <!DOCTYPE html>
                <html>
                <head><meta charset="UTF-8"><title>授權失敗</title></head>
                <body>
                <p>授權失敗，正在關閉視窗...</p>
                <script>
                  (function() {
                    var msg = '%s';
                    if (window.opener && !window.opener.closed) {
                      try {
                        window.opener.postMessage(
                          { type: 'shopee_oauth_error', message: msg },
                          window.location.origin
                        );
                      } catch (e) {
                        window.opener.postMessage(
                          { type: 'shopee_oauth_error', message: msg },
                          '*'
                        );
                      }
                      setTimeout(function() { window.close(); }, 500);
                    } else {
                      window.location.replace('/channels?oauth=error');
                    }
                  })();
                </script>
                </body>
                </html>
                """.formatted(safeMsg);

            return ResponseEntity.badRequest().body(html);
        }
    }
}
