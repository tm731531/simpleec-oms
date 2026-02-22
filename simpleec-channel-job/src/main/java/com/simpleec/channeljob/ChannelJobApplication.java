package com.simpleec.channeljob;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@SpringBootApplication(scanBasePackages = "com.simpleec")
public class ChannelJobApplication {
    public static void main(String[] args) {
        SpringApplication.run(ChannelJobApplication.class, args);
    }

    @Slf4j
    @Component
    public static class ChannelJobStartupLogger {
        @EventListener(ApplicationReadyEvent.class)
        public void onApplicationReady() {
            log.info("╔════════════════════════════════════════════════════════════╗");
            log.info("║  CHANNEL JOB Started (Layer C - Channel Integration)       ║");
            log.info("║                                                            ║");
            log.info("║  LISTENS TO (Consumer):                                   ║");
            log.info("║    • shopee.fast / shopee.slow                           ║");
            log.info("║    • momo.fast / momo.slow                               ║");
            log.info("║    • yahoo.fast / yahoo.slow                             ║");
            log.info("║    • pchome.fast / pchome.slow                           ║");
            log.info("║    • cyberbiz.fast / cyberbiz.slow                       ║");
            log.info("║    • easystore.fast / easystore.slow                     ║");
            log.info("║                                                            ║");
            log.info("║  PUBLISHES TO (Producer):                                 ║");
            log.info("║    • order.process (ORDER_UPSERT, ORDER_STATUS_CHANGE)   ║");
            log.info("║    • return.process (RETURN_UPSERT, RETURN_STATUS_CHG)   ║");
            log.info("║    • pack.sync (SYNC_PACK, UPDATE_PRICE)                 ║");
            log.info("║                                                            ║");
            log.info("║  ADAPTERS (6 platforms):                                  ║");
            log.info("║    • ShopifyAdapter (Mode A - Direct)                    ║");
            log.info("║    • CyberbizAdapter (Mode B - List+Detail)              ║");
            log.info("║    • ShopeeAdapter, MomoAdapter, YahooAdapter            ║");
            log.info("║    • PChomeAdapter, EasystoreAdapter                     ║");
            log.info("║                                                            ║");
            log.info("║  HANDLERS (per taskType from platform topics):            ║");
            log.info("║    • FETCH_ORDERS → Call platform API → ORDER_UPSERT    ║");
            log.info("║    • FETCH_RETURNS → Call platform API → RETURN_UPSERT  ║");
            log.info("║    • SYNC_PACK → Call platform API → Publish to pack.syn║");
            log.info("║                                                            ║");
            log.info("║  FLOW: Platform.{fast,slow} → Adapter → OMS Schema      ║");
            log.info("║                          → Hash-based dedup → Publish    ║");
            log.info("║                                                            ║");
            log.info("║  CONSUMER GROUPS: (TBD - per platform)                   ║");
            log.info("╚════════════════════════════════════════════════════════════╝");
        }
    }
}
