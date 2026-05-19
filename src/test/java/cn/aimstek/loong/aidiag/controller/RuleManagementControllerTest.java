package cn.aimstek.loong.aidiag.controller;

import cn.aimstek.loong.aidiag.rule.ConfigurableRuleEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * RuleManagementController 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class RuleManagementControllerTest {

    @Mock
    private ConfigurableRuleEngine ruleEngine;

    private RuleManagementController controller;

    @BeforeEach
    void setUp() {
        controller = new RuleManagementController(ruleEngine);
    }

    @Test
    void listRules_returnsAllRules() {
        // Given
        ConfigurableRuleEngine.RuleInfo rule1 = new ConfigurableRuleEngine.RuleInfo();
        rule1.setName("wait-split-stuck");
        rule1.setPriority(95);
        rule1.setEnabled(true);
        rule1.setHitCount(10);
        rule1.setAvgDurationMs(2.5);
        rule1.setLastHitAt(Instant.now());

        ConfigurableRuleEngine.RuleInfo rule2 = new ConfigurableRuleEngine.RuleInfo();
        rule2.setName("command-timeout");
        rule2.setPriority(75);
        rule2.setEnabled(true);
        rule2.setHitCount(5);

        when(ruleEngine.getRuleInfoList()).thenReturn(Arrays.asList(rule1, rule2));

        // When
        var response = controller.listRules();

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().isSuccess());
        assertEquals(2, response.getBody().getData().size());
        assertEquals("wait-split-stuck", response.getBody().getData().get(0).getName());
    }

    @Test
    void getStats_returnsEngineStats() {
        // Given
        ConfigurableRuleEngine.EngineStats stats = new ConfigurableRuleEngine.EngineStats();
        stats.setActiveRuleCount(10);
        stats.setTotalDiagnosisCount(100);
        stats.setAvgDurationMs(15.5);
        stats.setRecentMatchedRules(Arrays.asList("wait-split-stuck", "command-timeout"));

        when(ruleEngine.getEngineStats()).thenReturn(stats);

        // When
        var response = controller.getStats();

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().isSuccess());
        assertEquals(10, response.getBody().getData().getActiveRuleCount());
        assertEquals(100, response.getBody().getData().getTotalDiagnosisCount());
    }

    @Test
    void updatePriority_success() {
        // Given
        when(ruleEngine.ruleExists("wait-split-stuck")).thenReturn(true);

        RuleManagementController.PriorityRequest request = new RuleManagementController.PriorityRequest();
        request.setPriority(80);

        // When
        var response = controller.updatePriority("wait-split-stuck", request);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().isSuccess());
        verify(ruleEngine).setRulePriority("wait-split-stuck", 80);
    }

    @Test
    void updatePriority_ruleNotFound() {
        // Given
        when(ruleEngine.ruleExists("non-existent")).thenReturn(false);

        RuleManagementController.PriorityRequest request = new RuleManagementController.PriorityRequest();
        request.setPriority(80);

        // When
        var response = controller.updatePriority("non-existent", request);

        // Then
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertFalse(response.getBody().isSuccess());
    }

    @Test
    void updateEnabled_success() {
        // Given
        when(ruleEngine.ruleExists("wait-split-stuck")).thenReturn(true);

        RuleManagementController.EnabledRequest request = new RuleManagementController.EnabledRequest();
        request.setEnabled(false);

        // When
        var response = controller.updateEnabled("wait-split-stuck", request);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().isSuccess());
        verify(ruleEngine).setRuleEnabled("wait-split-stuck", false);
    }

    @Test
    void updateEnabled_ruleNotFound() {
        // Given
        when(ruleEngine.ruleExists("non-existent")).thenReturn(false);

        RuleManagementController.EnabledRequest request = new RuleManagementController.EnabledRequest();
        request.setEnabled(true);

        // When
        var response = controller.updateEnabled("non-existent", request);

        // Then
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertFalse(response.getBody().isSuccess());
    }

    @Test
    void reload_success() {
        // When
        var response = controller.reload();

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().isSuccess());
        verify(ruleEngine).reloadRules();
    }

    @Test
    void resetStats_success() {
        // When
        var response = controller.resetStats();

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().isSuccess());
        verify(ruleEngine).resetStats();
    }
}
