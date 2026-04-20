package cn.aimstek.loong.aidiag.controller;

import cn.aimstek.loong.aidiag.common.BaseResponse;
import cn.aimstek.loong.aidiag.common.Response;
import cn.aimstek.loong.aidiag.rule.ConfigurableRuleEngine;
import cn.aimstek.loong.aidiag.rule.DiagnoseRule;
import cn.aimstek.loong.aidiag.rule.RuleProperties;
import cn.aimstek.loong.aidiag.rule.RuleStatistics;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/rules")
public class RuleManageController {

    private final ConfigurableRuleEngine ruleEngine;
    private final RuleProperties ruleProperties;

    // ====== 查询类 API ======

    /**
     * GET /api/v1/rules
     * 获取所有规则列表（含 enabled/priority/统计数据），包括被禁用的规则
     */
    @GetMapping
    public Response<List<RuleInfo>> listRules() {
        try {
            List<RuleStatistics> stats = ruleEngine.getStatistics();
            Map<String, RuleStatistics> statsMap = stats.stream()
                    .collect(Collectors.toMap(RuleStatistics::getRuleName, s -> s, (a, b) -> a));

            List<RuleInfo> result = new ArrayList<>();
            for (DiagnoseRule rule : ruleEngine.getAllRules()) {
                RuleInfo info = new RuleInfo();
                info.setName(rule.getName());

                RuleProperties.RuleConfig config = ruleProperties.getRules().get(rule.getName());
                boolean enabled = config == null || config.isEnabled();
                int priority = (config != null && config.getPriority() != -1)
                        ? config.getPriority() : rule.getPriority();

                info.setEnabled(enabled);
                info.setPriority(priority);
                info.setParams(config != null ? config.getParams() : new HashMap<>());

                // 填充新增字段
                info.setType(ruleEngine.isBuiltinRule(rule.getName()) ? "builtin" : "expression");
                if (config != null) {
                    info.setDescription(config.getDescription());
                    info.setCondition(config.getCondition());
                    info.setOutput(config.getOutput());
                } else {
                    info.setDescription(rule.getDescription());
                }

                RuleStatistics stat = statsMap.get(rule.getName());
                if (stat != null) {
                    info.setHitCount(stat.getHitCount().get());
                    info.setAvgMatchTimeMs(stat.getAvgMatchTimeMs());
                    info.setAvgDiagnoseTimeMs(stat.getAvgDiagnoseTimeMs());
                    info.setMatchErrorCount(stat.getMatchErrorCount().get());
                    info.setDiagnoseErrorCount(stat.getDiagnoseErrorCount().get());
                    LocalDateTime lastHit = stat.getLastHitTime();
                    info.setLastHitTime(lastHit != null
                            ? lastHit.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null);
                }

                result.add(info);
            }

            // 按优先级降序排序
            result.sort((a, b) -> Integer.compare(b.getPriority(), a.getPriority()));

            return BaseResponse.success(result);
        } catch (Exception e) {
            log.error("获取规则列表失败", e);
            return BaseResponse.failure("RULE_LIST_ERROR", "获取规则列表失败: " + e.getMessage());
        }
    }

    /**
     * GET /api/v1/rules/statistics
     * 获取命中率统计
     */
    @GetMapping("/statistics")
    public Response<List<RuleStatistics>> getStatistics() {
        try {
            return BaseResponse.success(ruleEngine.getStatistics());
        } catch (Exception e) {
            log.error("获取统计数据失败", e);
            return BaseResponse.failure("STATISTICS_ERROR", "获取统计数据失败: " + e.getMessage());
        }
    }

    // ====== 修改类 API ======

    /**
     * PUT /api/v1/rules/{ruleName}/enabled
     * 启用/禁用指定规则
     */
    @PutMapping("/{ruleName}/enabled")
    public Response<Void> updateEnabled(@PathVariable String ruleName,
                                        @RequestBody Map<String, Boolean> body) {
        try {
            Boolean enabled = body.get("enabled");
            if (enabled == null) {
                return BaseResponse.failure("INVALID_PARAM", "参数 enabled 不能为空");
            }

            RuleProperties.RuleConfig config = ruleProperties.getRules()
                    .computeIfAbsent(ruleName, k -> new RuleProperties.RuleConfig());
            config.setEnabled(enabled);
            ruleEngine.reloadRules();

            log.info("规则 {} 已{}", ruleName, enabled ? "启用" : "禁用");
            return BaseResponse.success(null);
        } catch (Exception e) {
            log.error("修改规则启用状态失败: ruleName={}", ruleName, e);
            return BaseResponse.failure("UPDATE_ENABLED_ERROR", "修改规则启用状态失败: " + e.getMessage());
        }
    }

    /**
     * PUT /api/v1/rules/{ruleName}/priority
     * 修改指定规则优先级
     */
    @PutMapping("/{ruleName}/priority")
    public Response<Void> updatePriority(@PathVariable String ruleName,
                                         @RequestBody Map<String, Integer> body) {
        try {
            Integer priority = body.get("priority");
            if (priority == null) {
                return BaseResponse.failure("INVALID_PARAM", "参数 priority 不能为空");
            }

            RuleProperties.RuleConfig config = ruleProperties.getRules()
                    .computeIfAbsent(ruleName, k -> new RuleProperties.RuleConfig());
            config.setPriority(priority);
            ruleEngine.reloadRules();

            log.info("规则 {} 优先级已修改为 {}", ruleName, priority);
            return BaseResponse.success(null);
        } catch (Exception e) {
            log.error("修改规则优先级失败: ruleName={}", ruleName, e);
            return BaseResponse.failure("UPDATE_PRIORITY_ERROR", "修改规则优先级失败: " + e.getMessage());
        }
    }

    /**
     * PUT /api/v1/rules/{ruleName}/params
     * 修改规则参数
     */
    @PutMapping("/{ruleName}/params")
    public Response<Void> updateParams(@PathVariable String ruleName,
                                       @RequestBody Map<String, String> params) {
        try {
            RuleProperties.RuleConfig config = ruleProperties.getRules()
                    .computeIfAbsent(ruleName, k -> new RuleProperties.RuleConfig());
            config.getParams().putAll(params);
            ruleEngine.reloadRules();

            log.info("规则 {} 参数已更新: {}", ruleName, params);
            return BaseResponse.success(null);
        } catch (Exception e) {
            log.error("修改规则参数失败: ruleName={}", ruleName, e);
            return BaseResponse.failure("UPDATE_PARAMS_ERROR", "修改规则参数失败: " + e.getMessage());
        }
    }

    // ====== 操作类 API ======

    /**
     * POST /api/v1/rules/reload
     * 手动触发规则重载
     */
    @PostMapping("/reload")
    public Response<Void> reloadRules() {
        try {
            ruleEngine.reloadRules();
            return BaseResponse.success(null);
        } catch (Exception e) {
            log.error("规则重载失败", e);
            return BaseResponse.failure("RELOAD_ERROR", "规则重载失败: " + e.getMessage());
        }
    }

    /**
     * POST /api/v1/rules/statistics/reset
     * 重置统计数据
     */
    @PostMapping("/statistics/reset")
    public Response<Void> resetStatistics() {
        try {
            ruleEngine.resetStatistics();
            return BaseResponse.success(null);
        } catch (Exception e) {
            log.error("重置统计数据失败", e);
            return BaseResponse.failure("RESET_STATS_ERROR", "重置统计数据失败: " + e.getMessage());
        }
    }

    // ====== 规则CRUD API ======

    /**
     * POST /api/v1/rules
     * 创建表达式规则
     */
    @PostMapping
    public Response<Void> createExpressionRule(@RequestBody Map<String, Object> body) {
        try {
            String name = (String) body.get("name");
            if (name == null || name.isBlank()) {
                return BaseResponse.failure("INVALID_PARAM", "规则名称不能为空");
            }
            String condition = (String) body.get("condition");
            if (condition == null || condition.isBlank()) {
                return BaseResponse.failure("INVALID_PARAM", "表达式规则必须包含condition字段");
            }
            if (ruleProperties.getRules().containsKey(name) || ruleEngine.getAllRules().stream().anyMatch(r -> r.getName().equals(name))) {
                return BaseResponse.failure("RULE_EXISTS", "规则名称已存在: " + name);
            }

            RuleProperties.RuleConfig config = new RuleProperties.RuleConfig();
            config.setCondition(condition);
            config.setPriority(body.containsKey("priority") ? ((Number) body.get("priority")).intValue() : 500);
            config.setEnabled(body.containsKey("enabled") ? (Boolean) body.get("enabled") : true);
            config.setDescription((String) body.get("description"));

            if (body.containsKey("params") && body.get("params") instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, String> params = (Map<String, String>) body.get("params");
                config.setParams(params);
            }

            if (body.containsKey("output") && body.get("output") instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> outputMap = (Map<String, Object>) body.get("output");
                RuleProperties.OutputConfig output = parseOutputConfig(outputMap);
                config.setOutput(output);
            }

            ruleEngine.addExpressionRule(name, config);

            log.info("创建表达式规则: {}", name);
            return BaseResponse.success(null);
        } catch (IllegalArgumentException e) {
            return BaseResponse.failure("INVALID_PARAM", e.getMessage());
        } catch (Exception e) {
            log.error("创建表达式规则失败", e);
            return BaseResponse.failure("CREATE_RULE_ERROR", "创建表达式规则失败: " + e.getMessage());
        }
    }

    /**
     * DELETE /api/v1/rules/{ruleName}
     * 删除表达式规则（不允许删除内置规则）
     */
    @DeleteMapping("/{ruleName}")
    public Response<Void> deleteExpressionRule(@PathVariable String ruleName) {
        try {
            if (ruleEngine.isBuiltinRule(ruleName)) {
                return BaseResponse.failure("BUILTIN_RULE", "不允许删除内置规则: " + ruleName);
            }
            ruleProperties.getRules().remove(ruleName);
            ruleEngine.removeExpressionRule(ruleName);

            log.info("删除表达式规则: {}", ruleName);
            return BaseResponse.success(null);
        } catch (IllegalArgumentException e) {
            return BaseResponse.failure("INVALID_PARAM", e.getMessage());
        } catch (Exception e) {
            log.error("删除表达式规则失败: ruleName={}", ruleName, e);
            return BaseResponse.failure("DELETE_RULE_ERROR", "删除表达式规则失败: " + e.getMessage());
        }
    }

    /**
     * PUT /api/v1/rules/{ruleName}/description
     * 修改规则描述
     */
    @PutMapping("/{ruleName}/description")
    public Response<Void> updateDescription(@PathVariable String ruleName,
                                            @RequestBody Map<String, String> body) {
        try {
            String description = body.get("description");
            RuleProperties.RuleConfig config = ruleProperties.getRules()
                    .computeIfAbsent(ruleName, k -> new RuleProperties.RuleConfig());
            config.setDescription(description);
            ruleEngine.reloadRules();

            log.info("规则 {} 描述已更新", ruleName);
            return BaseResponse.success(null);
        } catch (Exception e) {
            log.error("修改规则描述失败: ruleName={}", ruleName, e);
            return BaseResponse.failure("UPDATE_DESC_ERROR", "修改规则描述失败: " + e.getMessage());
        }
    }

    /**
     * PUT /api/v1/rules/{ruleName}/output
     * 修改诊断输出配置
     */
    @PutMapping("/{ruleName}/output")
    public Response<Void> updateOutput(@PathVariable String ruleName,
                                       @RequestBody RuleProperties.OutputConfig output) {
        try {
            RuleProperties.RuleConfig config = ruleProperties.getRules()
                    .computeIfAbsent(ruleName, k -> new RuleProperties.RuleConfig());
            config.setOutput(output);
            ruleEngine.reloadRules();

            log.info("规则 {} 诊断输出配置已更新", ruleName);
            return BaseResponse.success(null);
        } catch (Exception e) {
            log.error("修改诊断输出配置失败: ruleName={}", ruleName, e);
            return BaseResponse.failure("UPDATE_OUTPUT_ERROR", "修改诊断输出配置失败: " + e.getMessage());
        }
    }

    /**
     * PUT /api/v1/rules/{ruleName}/condition
     * 修改表达式规则条件（内置规则不允许修改）
     */
    @PutMapping("/{ruleName}/condition")
    public Response<Void> updateCondition(@PathVariable String ruleName,
                                          @RequestBody Map<String, String> body) {
        try {
            if (ruleEngine.isBuiltinRule(ruleName)) {
                return BaseResponse.failure("BUILTIN_RULE", "内置规则不允许修改条件: " + ruleName);
            }
            String condition = body.get("condition");
            if (condition == null || condition.isBlank()) {
                return BaseResponse.failure("INVALID_PARAM", "条件表达式不能为空");
            }

            RuleProperties.RuleConfig config = ruleProperties.getRules()
                    .computeIfAbsent(ruleName, k -> new RuleProperties.RuleConfig());
            config.setCondition(condition);
            ruleEngine.reloadRules();

            log.info("规则 {} 条件已更新为: {}", ruleName, condition);
            return BaseResponse.success(null);
        } catch (Exception e) {
            log.error("修改规则条件失败: ruleName={}", ruleName, e);
            return BaseResponse.failure("UPDATE_CONDITION_ERROR", "修改规则条件失败: " + e.getMessage());
        }
    }

    // ====== 文档检索配置 API ======

    /**
     * GET /api/v1/rules/doc-search/config
     * 获取文档检索配置
     */
    @GetMapping("/doc-search/config")
    public Response<RuleProperties.DocSearchConfig> getDocSearchConfig() {
        try {
            return BaseResponse.success(ruleProperties.getDocSearch());
        } catch (Exception e) {
            log.error("获取文档检索配置失败", e);
            return BaseResponse.failure("DOC_SEARCH_CONFIG_ERROR", "获取文档检索配置失败: " + e.getMessage());
        }
    }

    /**
     * PUT /api/v1/rules/doc-search/config
     * 修改文档检索配置（仅更新传入的有效字段）
     */
    @PutMapping("/doc-search/config")
    public Response<Void> updateDocSearchConfig(@RequestBody Map<String, Object> configMap) {
        try {
            RuleProperties.DocSearchConfig current = ruleProperties.getDocSearch();

            if (configMap.containsKey("topK")) {
                Object topKObj = configMap.get("topK");
                if (topKObj instanceof Number) {
                    int topK = ((Number) topKObj).intValue();
                    if (topK > 0) current.setTopK(topK);
                }
            }
            if (configMap.containsKey("similarityThreshold")) {
                Object thresholdObj = configMap.get("similarityThreshold");
                if (thresholdObj instanceof Number) {
                    double threshold = ((Number) thresholdObj).doubleValue();
                    if (threshold > 0) current.setSimilarityThreshold(threshold);
                }
            }
            if (configMap.containsKey("preSearchEnabled")) {
                Object preSearchObj = configMap.get("preSearchEnabled");
                if (preSearchObj instanceof Boolean) {
                    current.setPreSearchEnabled((Boolean) preSearchObj);
                }
            }

            if (configMap.containsKey("hybridEnabled")) {
                Object hybridEnabledObj = configMap.get("hybridEnabled");
                if (hybridEnabledObj instanceof Boolean) {
                    current.setHybridEnabled((Boolean) hybridEnabledObj);
                }
            }
            if (configMap.containsKey("vectorWeight")) {
                Object vectorWeightObj = configMap.get("vectorWeight");
                if (vectorWeightObj instanceof Number) {
                    double vectorWeight = ((Number) vectorWeightObj).doubleValue();
                    if (vectorWeight >= 0) current.setVectorWeight(vectorWeight);
                }
            }
            if (configMap.containsKey("keywordWeight")) {
                Object keywordWeightObj = configMap.get("keywordWeight");
                if (keywordWeightObj instanceof Number) {
                    double keywordWeight = ((Number) keywordWeightObj).doubleValue();
                    if (keywordWeight >= 0) current.setKeywordWeight(keywordWeight);
                }
            }

            log.info("文档检索配置已更新: topK={}, similarityThreshold={}, preSearchEnabled={}, hybridEnabled={}, vectorWeight={}, keywordWeight={}",
                    current.getTopK(), current.getSimilarityThreshold(), current.isPreSearchEnabled(),
                    current.isHybridEnabled(), current.getVectorWeight(), current.getKeywordWeight());
            return BaseResponse.success(null);
        } catch (Exception e) {
            log.error("修改文档检索配置失败", e);
            return BaseResponse.failure("UPDATE_DOC_SEARCH_ERROR", "修改文档检索配置失败: " + e.getMessage());
        }
    }

    // ====== 辅助方法 ======

    @SuppressWarnings("unchecked")
    private RuleProperties.OutputConfig parseOutputConfig(Map<String, Object> outputMap) {
        RuleProperties.OutputConfig output = new RuleProperties.OutputConfig();
        output.setSummary((String) outputMap.get("summary"));
        if (outputMap.containsKey("actions") && outputMap.get("actions") instanceof List) {
            output.setActions((List<String>) outputMap.get("actions"));
        }
        if (outputMap.containsKey("rootCauses") && outputMap.get("rootCauses") instanceof List) {
            List<Map<String, String>> causesList = (List<Map<String, String>>) outputMap.get("rootCauses");
            List<RuleProperties.RootCauseConfig> rootCauses = causesList.stream().map(m -> {
                RuleProperties.RootCauseConfig rc = new RuleProperties.RootCauseConfig();
                rc.setTitle(m.get("title"));
                rc.setDescription(m.get("description"));
                return rc;
            }).collect(Collectors.toList());
            output.setRootCauses(rootCauses);
        }
        return output;
    }

    // ====== 内部 DTO ======

    @Data
    public static class RuleInfo {
        private String name;
        private String description;
        private String type;
        private int priority;
        private boolean enabled;
        private long hitCount;
        private double avgMatchTimeMs;
        private double avgDiagnoseTimeMs;
        private String lastHitTime;
        private long matchErrorCount;
        private long diagnoseErrorCount;
        private Map<String, String> params;
        private String condition;
        private RuleProperties.OutputConfig output;
    }
}
