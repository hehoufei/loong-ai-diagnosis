package cn.aimstek.loong.aidiag.llm;

import cn.aimstek.loong.aidiag.client.dto.PlatformCommand;
import cn.aimstek.loong.aidiag.client.dto.PlatformTaskItem;
import cn.aimstek.loong.aidiag.context.DiagnosisContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Prompt 模板加载器。
 * 从 resources/prompts/ 加载模板文件并缓存。
 */
@Slf4j
@Component
public class PromptTemplateLoader {

    private String systemRole;
    private String businessContext;
    private String decisionTree;
    private String outputFormat;

    @PostConstruct
    public void init() {
        this.systemRole = loadTemplate("prompts/system-role.txt");
        this.businessContext = loadTemplate("prompts/business-context.txt");
        this.decisionTree = loadTemplate("prompts/decision-tree.txt");
        this.outputFormat = loadTemplate("prompts/output-format.txt");
        log.info("PromptTemplateLoader 初始化完成");
    }

    /**
     * 获取 system prompt（角色设定）。
     */
    public String getSystemPrompt() {
        return systemRole;
    }

    /**
     * 构建 user prompt。
     */
    public String buildUserPrompt(DiagnosisContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append(businessContext).append("\n\n");
        sb.append(decisionTree).append("\n\n");
        sb.append("# 当前任务数据\n\n");
        sb.append(renderTaskData(context)).append("\n\n");
        sb.append(outputFormat);
        return sb.toString();
    }

    /**
     * 将 Context 序列化为结构化文本。
     */
    public String renderTaskData(DiagnosisContext context) {
        StringBuilder sb = new StringBuilder();

        // 大任务
        sb.append("## 大任务\n");
        if (context.getTask() != null) {
            var task = context.getTask();
            sb.append("- 任务号: ").append(safe(task.getTaskNo())).append("\n");
            sb.append("- 状态: ").append(safe(task.getTaskState())).append("\n");
            sb.append("- 已卡住: ").append(context.stuckSeconds()).append(" 秒\n");
            sb.append("- 任务类型: ").append(safe(task.getTaskType())).append("\n");
            sb.append("- 业务类型: ").append(safe(task.getBizType())).append("\n");
            sb.append("- 暂停状态: ").append(safe(task.getPaused())).append("\n");
            sb.append("- 起点: ").append(safe(task.getStartNode())).append("\n");
            sb.append("- 终点: ").append(safe(task.getEndNode())).append("\n");
            sb.append("- 创建时间: ").append(safe(task.getCreateTime())).append("\n");
            sb.append("- 开始时间: ").append(safe(task.getStartTime())).append("\n");
            sb.append("- 任务组: ").append(safe(task.getGroupCode())).append("\n");
            if (task.getErrorMessage() != null && !task.getErrorMessage().isEmpty()) {
                sb.append("- 错误信息: ").append(task.getErrorMessage()).append("\n");
            }
        }

        // 子任务列表
        List<PlatformTaskItem> items = context.getItems();
        if (items != null && !items.isEmpty()) {
            sb.append("\n## 子任务列表（共 ").append(items.size()).append(" 个）\n");
            sb.append("| 序号 | 子任务号 | 状态 | 设备 | 调度通道 | 暂停 | 起点 | 终点 |\n");
            sb.append("|---|---|---|---|---|---|---|---|\n");
            int idx = 1;
            for (PlatformTaskItem item : items) {
                sb.append("| ").append(idx++).append(" | ")
                        .append(safe(item.getTaskItemNo())).append(" | ")
                        .append(safe(item.getTaskItemState())).append(" | ")
                        .append(safe(item.getDeviceCode())).append(" | ")
                        .append(safe(item.getScheduleChannel())).append(" | ")
                        .append(safe(item.getPaused())).append(" | ")
                        .append(safe(item.getStartNode())).append(" | ")
                        .append(safe(item.getEndNode())).append(" |\n");
            }
        }

        // 所有指令
        List<PlatformCommand> commands = context.allCommands();
        if (commands != null && !commands.isEmpty()) {
            sb.append("\n## 指令列表（共 ").append(commands.size()).append(" 个）\n");
            sb.append("| 子任务号 | 指令号 | 状态 | PLC任务号 | 设备 | 开始时间 |\n");
            sb.append("|---|---|---|---|---|---|\n");
            for (PlatformCommand cmd : commands) {
                sb.append("| ").append(safe(cmd.getTaskItemNo())).append(" | ")
                        .append(safe(cmd.getCommandNo())).append(" | ")
                        .append(safe(cmd.getCommandState())).append(" | ")
                        .append(safe(cmd.getPlcTaskNo())).append(" | ")
                        .append(safe(cmd.getDeviceCode())).append(" | ")
                        .append(safe(cmd.getStartTime())).append(" |\n");
            }
        }

        // 异常信号（自动检测）
        String signals = detectAbnormalSignals(context);
        if (!signals.isEmpty()) {
            sb.append("\n## 异常信号\n").append(signals);
        }

        return sb.toString();
    }

    /**
     * 异常信号自动检测：Command 超时、子任务暂停等。
     */
    private String detectAbnormalSignals(DiagnosisContext context) {
        StringBuilder sb = new StringBuilder();
        LocalDateTime now = context.getNow() != null ? context.getNow() : LocalDateTime.now();

        // 指令超时检测
        for (PlatformCommand cmd : context.allCommands()) {
            if (("SENT".equals(cmd.getCommandState()) || "ACKED".equals(cmd.getCommandState()))
                    && cmd.getStartTime() != null) {
                long elapsed = Duration.between(cmd.getStartTime(), now).getSeconds();
                if (elapsed > 300) {
                    sb.append("- 指令 ").append(cmd.getCommandNo())
                            .append(" 状态 ").append(cmd.getCommandState())
                            .append(" 已持续 ").append(elapsed).append(" 秒未完成\n");
                }
                if ("SENT".equals(cmd.getCommandState()) && (cmd.getPlcTaskNo() == null || cmd.getPlcTaskNo().isEmpty())) {
                    sb.append("- 指令 ").append(cmd.getCommandNo())
                            .append(" 已 SENT 但 PLC 未回填 plcTaskNo\n");
                }
            }
        }

        // 子任务暂停检测
        if (context.getItems() != null) {
            for (PlatformTaskItem item : context.getItems()) {
                if ("YES".equals(item.getPaused())) {
                    sb.append("- 子任务 ").append(item.getTaskItemNo()).append(" 已暂停\n");
                }
            }
        }

        // 任务暂停
        if (context.getTask() != null && "YES".equals(context.getTask().getPaused())) {
            sb.append("- 大任务 ").append(context.getTask().getTaskNo()).append(" 已暂停\n");
        }

        return sb.toString();
    }

    private String loadTemplate(String path) {
        try {
            ClassPathResource resource = new ClassPathResource(path);
            return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("加载模板失败: {}", path, e);
            return "";
        }
    }

    private String safe(Object v) {
        return v == null ? "" : v.toString();
    }
}
