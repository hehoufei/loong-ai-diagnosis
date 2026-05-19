package cn.aimstek.loong.aidiag.controller;

import cn.aimstek.loong.aidiag.common.BaseResponse;
import cn.aimstek.loong.aidiag.common.Response;
import cn.aimstek.loong.aidiag.rule.ConfigurableRuleEngine;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 规则管理 Controller。
 * 提供规则列表查询、统计、优先级调整、启用/禁用、重载、重置统计等管理接口。
 * 所有修改仅内存生效，不持久化。
 */
@Slf4j
@Tag(name = "规则管理接口", description = "规则引擎运行时管理")
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/rules")
public class RuleManagementController {

    private final ConfigurableRuleEngine ruleEngine;

    /**
     * 24.1 返回当前所有规则列表（名称/优先级/启用状态/命中次数/平均耗时/最后命中时间）
     */
    @Operation(summary = "获取规则列表")
    @GetMapping
    public ResponseEntity<Response<List<ConfigurableRuleEngine.RuleInfo>>> listRules() {
        List<ConfigurableRuleEngine.RuleInfo> rules = ruleEngine.getRuleInfoList();
        return ResponseEntity.ok(BaseResponse.success(rules));
    }

    /**
     * 24.2 返回规则引擎统计（活跃规则数/总诊断次数/平均耗时/最近命中规则）
     */
    @Operation(summary = "获取规则引擎统计")
    @GetMapping("/stats")
    public ResponseEntity<Response<ConfigurableRuleEngine.EngineStats>> getStats() {
        ConfigurableRuleEngine.EngineStats stats = ruleEngine.getEngineStats();
        return ResponseEntity.ok(BaseResponse.success(stats));
    }

    /**
     * 24.3 更新规则优先级（内存生效，不持久化）
     */
    @Operation(summary = "更新规则优先级")
    @PutMapping("/{name}/priority")
    public ResponseEntity<Response<Void>> updatePriority(
            @PathVariable String name,
            @RequestBody PriorityRequest request) {
        if (!ruleEngine.ruleExists(name)) {
            return ResponseEntity.status(404)
                    .body(BaseResponse.failure("RULE_NOT_FOUND", "规则不存在: " + name));
        }
        ruleEngine.setRulePriority(name, request.getPriority());
        return ResponseEntity.ok(BaseResponse.success(null));
    }

    /**
     * 24.4 启用/禁用规则（内存生效）
     */
    @Operation(summary = "启用/禁用规则")
    @PutMapping("/{name}/enabled")
    public ResponseEntity<Response<Void>> updateEnabled(
            @PathVariable String name,
            @RequestBody EnabledRequest request) {
        if (!ruleEngine.ruleExists(name)) {
            return ResponseEntity.status(404)
                    .body(BaseResponse.failure("RULE_NOT_FOUND", "规则不存在: " + name));
        }
        ruleEngine.setRuleEnabled(name, request.isEnabled());
        return ResponseEntity.ok(BaseResponse.success(null));
    }

    /**
     * 24.5 重新从 YAML 加载规则配置
     */
    @Operation(summary = "重新加载规则配置")
    @PostMapping("/reload")
    public ResponseEntity<Response<Void>> reload() {
        ruleEngine.reloadRules();
        return ResponseEntity.ok(BaseResponse.success(null));
    }

    /**
     * 24.6 重置命中统计计数器
     */
    @Operation(summary = "重置规则统计")
    @PostMapping("/reset-stats")
    public ResponseEntity<Response<Void>> resetStats() {
        ruleEngine.resetStats();
        return ResponseEntity.ok(BaseResponse.success(null));
    }

    // ==================== 请求体 DTO ====================

    @Data
    public static class PriorityRequest {
        private int priority;
    }

    @Data
    public static class EnabledRequest {
        private boolean enabled;
    }
}
