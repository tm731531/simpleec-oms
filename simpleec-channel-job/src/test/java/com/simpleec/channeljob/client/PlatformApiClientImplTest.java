package com.simpleec.channeljob.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlatformApiClientImplTest {

    @Mock
    private RestTemplate restTemplate;

    @InjectMocks
    private PlatformApiClientImpl platformApiClient;

    @Test
    void testHealthCheck_Success() {
        // Arrange
        when(restTemplate.getForObject(anyString(), eq(String.class))).thenReturn("OK");

        // Act
        int status = platformApiClient.healthCheck("shopee", "valid-token");

        // Assert
        assertEquals(200, status);
    }

    @Test
    void testHealthCheck_AllPlatforms() {
        // Arrange
        when(restTemplate.getForObject(anyString(), eq(String.class))).thenReturn("OK");

        // Act & Assert
        String[] platforms = {"momo", "shopee", "yahoo", "pchome", "cyberbiz", "shopline", "shopify"};
        for (String platform : platforms) {
            int status = platformApiClient.healthCheck(platform, "token");
            assertEquals(200, status);
        }
    }

    @Test
    void testHealthCheck_Exception() {
        // Arrange
        when(restTemplate.getForObject(anyString(), eq(String.class)))
            .thenThrow(new RestClientException("Connection refused"));

        // Act
        int status = platformApiClient.healthCheck("shopee", "token");

        // Assert
        assertEquals(500, status);
    }

    @Test
    void testPlatformHealthCheck_Success() {
        // Arrange
        when(restTemplate.getForObject(anyString(), eq(String.class))).thenReturn("OK");

        // Act
        int status = platformApiClient.platformHealthCheck("shopee");

        // Assert
        assertEquals(200, status);
    }

    @Test
    void testPlatformHealthCheck_AllPlatforms() {
        // Arrange
        when(restTemplate.getForObject(anyString(), eq(String.class))).thenReturn("OK");

        // Act & Assert
        String[] platforms = {"momo", "shopee", "yahoo", "pchome", "cyberbiz", "shopline", "shopify"};
        for (String platform : platforms) {
            int status = platformApiClient.platformHealthCheck(platform);
            assertEquals(200, status);
        }
    }

    @Test
    void testPlatformHealthCheck_Exception() {
        // Arrange
        when(restTemplate.getForObject(anyString(), eq(String.class)))
            .thenThrow(new RestClientException("Service unavailable"));

        // Act
        int status = platformApiClient.platformHealthCheck("shopee");

        // Assert
        assertEquals(503, status);
    }
}
