package cn.aimstek.loong.aidiag.controller;

import cn.aimstek.loong.aidiag.config.PlatformProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * MapProxyController 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class MapProxyControllerTest {

    @Mock
    private PlatformProperties platformProperties;

    @Mock
    private RestTemplate restTemplate;

    private MapProxyController controller;

    @BeforeEach
    void setUp() {
        controller = new MapProxyController(platformProperties, restTemplate);
    }

    @Test
    void nodeList_success() {
        // Given
        when(platformProperties.getEffectiveMapBaseUrl()).thenReturn("http://localhost:18081");
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{\"success\": true, \"data\": []}", HttpStatus.OK));

        // When
        ResponseEntity<String> response = controller.nodeList("{\"param\": \"value\"}");

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("{\"success\": true, \"data\": []}", response.getBody());
    }

    @Test
    void nodeList_platformError() {
        // Given
        when(platformProperties.getEffectiveMapBaseUrl()).thenReturn("http://localhost:18081");
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenThrow(new RestClientException("Connection refused"));

        // When & Then
        assertThrows(RestClientException.class, () -> {
            controller.nodeList("{\"param\": \"value\"}");
        });
    }

    @Test
    void nodeList_non2xxResponse() {
        // Given
        when(platformProperties.getEffectiveMapBaseUrl()).thenReturn("http://localhost:18081");
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{\"error\": \"Internal error\"}", HttpStatus.INTERNAL_SERVER_ERROR));

        // When & Then
        assertThrows(RestClientException.class, () -> {
            controller.nodeList("{\"param\": \"value\"}");
        });
    }

    @Test
    void viewDetail_success() {
        // Given
        when(platformProperties.getEffectiveMapBaseUrl()).thenReturn("http://localhost:18081");
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{\"success\": true, \"data\": {\"viewId\": \"123\"}}", HttpStatus.OK));

        // When
        ResponseEntity<String> response = controller.viewDetail("{\"viewId\": \"123\"}");

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("{\"success\": true, \"data\": {\"viewId\": \"123\"}}", response.getBody());
    }

    @Test
    void viewDetail_platformError() {
        // Given
        when(platformProperties.getEffectiveMapBaseUrl()).thenReturn("http://localhost:18081");
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenThrow(new RestClientException("Connection refused"));

        // When & Then
        assertThrows(RestClientException.class, () -> {
            controller.viewDetail("{\"viewId\": \"123\"}");
        });
    }

    @Test
    void viewDetail_non2xxResponse() {
        // Given
        when(platformProperties.getEffectiveMapBaseUrl()).thenReturn("http://localhost:18081");
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{\"error\": \"Internal error\"}", HttpStatus.INTERNAL_SERVER_ERROR));

        // When & Then
        assertThrows(RestClientException.class, () -> {
            controller.viewDetail("{\"viewId\": \"123\"}");
        });
    }
}