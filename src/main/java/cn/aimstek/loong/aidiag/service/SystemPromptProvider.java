package cn.aimstek.loong.aidiag.service;

import cn.aimstek.loong.aidiag.config.DiagnosisPromptProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Agent 系统提示词构建器。
 * 复用 DiagnosisPromptProperties 中的诊断规则配置，
 * 构建适配 Agent 对话模式的系统提示词（Markdown 格式输出）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SystemPromptProvider {

    private final DiagnosisPromptProperties promptProps;

    /**
     * 构建 Agent 系统提示词，包含：
     * 1. 角色设定
     * 2. WCS 业务背景
     * 3. 任务生命周期
     * 4. 诊断决策树
     * 5. 常见故障场景
     * 6. 可用工具使用指南
     * 7. 输出格式要求（Markdown）
     */
    public String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder();

        // 1. 角色设定
        sb.append(promptProps.getRole()).append("\n\n");

        // 2. 业务背景
        String ctx = promptProps.getBusinessContext();
        if (ctx != null && !ctx.isBlank()) {
            sb.append(ctx).append("\n\n");
        }

        // 3. 任务生命周期
        if (!promptProps.getLifecycle().isEmpty()) {
            sb.append("## 任务正常流转顺序\n");
            for (DiagnosisPromptProperties.LifecycleRule rule : promptProps.getLifecycle()) {
                sb.append("### ").append(rule.getTitle()).append("\n");
                sb.append(rule.getContent()).append("\n");
            }
            sb.append("\n");
        }

        // 4. 诊断决策树
        if (!promptProps.getDecisionTree().isEmpty()) {
            sb.append("## 诊断决策树（按此顺序逐步排查）\n");
            for (DiagnosisPromptProperties.DecisionStep step : promptProps.getDecisionTree()) {
                sb.append("### 第").append(step.getStep()).append("步: ").append(step.getTitle()).append("\n");
                for (DiagnosisPromptProperties.DecisionRule rule : step.getRules()) {
                    sb.append("- **").append(rule.getCondition()).append("** → ").append(rule.getConclusion());
                    if (rule.getCauses() != null && !rule.getCauses().isEmpty()) {
                        sb.append("，可能原因: ").append(rule.getCauses());
                    }
                    sb.append("\n");
                }
                sb.append("\n");
            }
        }

        // 5. 常见故障场景
        if (!promptProps.getFaultScenarios().isEmpty()) {
            sb.append("## 常见故障场景\n");
            for (DiagnosisPromptProperties.FaultScenario scenario : promptProps.getFaultScenarios()) {
                sb.append("- **").append(scenario.getName()).append("**: ").append(scenario.getDescription()).append("\n");
            }
            sb.append("\n");
        }

        // 点位冲突补充说明
        String conflictHint = promptProps.getConflictHint();
        if (conflictHint != null && !conflictHint.isBlank()) {
            sb.append("## 点位冲突补充说明\n");
            sb.append(conflictHint).append("\n\n");
        }

        // 6. 可用工具使用指南
        sb.append("## 可用工具使用指南\n");
        sb.append("你可以通过以下工具按需查询数据，请根据诊断需要自主决定调用顺序和次数：\n\n");
        sb.append("1. **getTaskDetail** — 根据任务ID查询主任务详情（状态、处理阶段、起终点、容器号等）\n");
        sb.append("2. **getTaskItems** — 查询主任务下的所有子任务列表（状态、设备、起终点）\n");
        sb.append("3. **getTickets** — 查询任务下的所有执行单（状态、PLC任务号、设备）\n");
        sb.append("4. **queryLogs** — 查询任务相关的系统日志（按时间排序）\n");
        sb.append("5. **checkPointConflicts** — 查询点位锁冲突（排除当前任务自身的锁）\n");
        sb.append("6. **searchDocs** — 从运维文档和设备手册中检索相关知识片段\n\n");
        sb.append("**建议诊断流程**：先查主任务详情判断大阶段 → 根据需要查子任务/执行单 → 查日志补充分析 → 如有路径问题查点位冲突 → 如需参考文档则检索知识库\n\n");

        // 7. 输出格式要求（Markdown，非 JSON）
        sb.append("## 输出格式要求\n");
        sb.append("请使用 Markdown 格式输出诊断结果，结构如下：\n\n");
        sb.append("### 诊断摘要\n");
        sb.append("一句话说明任务当前卡在生命周期的哪个阶段及原因。\n\n");
        sb.append("### 根因分析\n");
        sb.append("列出每个根因的标题和详细描述，包括你的判断依据和引用的数据。\n\n");
        sb.append("### 操作建议\n");
        sb.append("列出具体可操作的处理建议。\n\n");
        sb.append("请严格按照诊断决策树的步骤逐步分析，确保分析过程有理有据。\n");

        return sb.toString();
    }
}
