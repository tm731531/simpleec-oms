package com.simpleec.channel.config;

import com.simpleec.channel.adapter.ChannelAdapter;
import com.simpleec.channel.adapter.CyberbizAdapter;
import com.simpleec.channel.adapter.EasystoreAdapter;
import com.simpleec.channel.adapter.ShopifyAdapter;
import com.simpleec.channel.adapter.ShopeeAdapter;
import com.simpleec.channel.api.CyberbizApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * Channel Adapter 配置類
 * 統一管理所有通路適配器為 Spring Bean
 */
@Configuration
@RequiredArgsConstructor
public class ChannelAdapterConfig {

    private final CyberbizApiClient cyberbizApiClient;
    private final RestTemplateBuilder restTemplateBuilder;

    /**
     * 建立 RestTemplate bean 用於 HTTP 呼叫
     */
    @Bean
    public RestTemplate restTemplate() {
        return restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(10))
                .setReadTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * 註冊 Cyberbiz 適配器
     */
    @Bean
    public ChannelAdapter cyberbizAdapter() {
        return new CyberbizAdapter(cyberbizApiClient);
    }

    /**
     * 註冊 Shopify 適配器
     */
    @Bean
    public ChannelAdapter shopifyAdapter() {
        return new ShopifyAdapter();
    }

    /**
     * 註冊 Easystore 適配器
     */
    @Bean
    public ChannelAdapter easystoreAdapter() {
        return new EasystoreAdapter();
    }

    /**
     * 註冊 Shopee 適配器
     */
    @Bean
    public ChannelAdapter shopeeAdapter() {
        return new ShopeeAdapter();
    }
}
