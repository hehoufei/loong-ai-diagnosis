package cn.aimstek.loong.aidiag.context;

import cn.aimstek.loong.aidiag.client.dto.*;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 诊断上下文：规则引擎和 LLM 共用的数据结构。
 */
@Data
@Builder
public class DiagnosisContext {

    private String traceId;
    private PlatformTask task;
    private List<PlatformTaskItem> items;
    private List<PlatformTaskRelation> relations;
    private PlatformTaskGroup group;
    private LocalDateTime now;

    /**
     * 获取所有指令（扁平化）。
     */
    public List<PlatformCommand> allCommands() {
        if (items == null) {
            return List.of();
        }
        return items.stream()
                .flatMap(item -> item.getCommands().stream())
                .collect(Collectors.toList());
    }

    /**
     * 获取非终态子任务。
     */
    public List<PlatformTaskItem> activeItems() {
        if (items == null) {
            return List.of();
        }
        return items.stream()
                .filter(item -> !"SUCCESS".equals(item.getTaskItemState()) && !"CANCEL".equals(item.getTaskItemState()))
                .collect(Collectors.toList());
    }

    /**
     * 获取 RUNNING 状态子任务。
     */
    public List<PlatformTaskItem> runningItems() {
        if (items == null) {
            return List.of();
        }
        return items.stream()
                .filter(item -> "RUNNING".equals(item.getTaskItemState()))
                .collect(Collectors.toList());
    }

    /**
     * 计算卡住时长（秒）。
     */
    public long stuckSeconds() {
        if (task == null || task.getStartTime() == null || now == null) {
            return 0;
        }
        return java.time.Duration.between(task.getStartTime(), now).getSeconds();
    }

    /**
     * 判断是否终态。
     */
    public boolean isTerminal() {
        if (task == null || task.getTaskState() == null) {
            return false;
        }
        String state = task.getTaskState();
        return "SUCCESS".equals(state) || "CANCEL".equals(state) || "MANUAL_SUCCESS".equals(state);
    }

    /**
     * 判断是否任务组任务。
     */
    public boolean isGroupTask() {
        return task != null && task.getGroupCode() != null && !task.getGroupCode().isEmpty();
    }
}
