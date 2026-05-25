package cn.aimstek.loong.aidiag.rule;

import cn.aimstek.loong.aidiag.dto.PointConflict;
import cn.aimstek.loong.aidiag.dto.TaskDetail;
import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 诊断上下文，封装规则执行所需的所有数据。
 * 适配 loong-platform 重构后的三级任务模型：Task → TaskItem → Command。
 */
@Data
@Builder
public class DiagnosisContext {
    /** 主任务详情 */
    private TaskDetail detail;
    /** 点位冲突列表 */
    private List<PointConflict> conflicts;
    /** 系统日志 */
    private List<String> logs;
    /** 向量检索结果（融入规则链路） */
    @Builder.Default
    private List<String> relevantDocs = new ArrayList<>();
    /** 链路追踪ID */
    private String traceId;
    /** 当前诊断模式 */
    private String diagnosisMode;

    // ====== 便捷访问方法 ======

    /**
     * 获取主任务的 taskState，防空。
     * 取值：WAIT_SPLIT/WAIT_PLAN/RUNNING/SUCCESS/CANCEL/MANUAL_SUCCESS。
     */
    public String getTaskState() {
        return detail != null ? detail.getTaskState() : null;
    }

    /** 是否暂停（detail.paused == "Y"） */
    public boolean isPaused() {
        return detail != null && "Y".equalsIgnoreCase(detail.getPaused());
    }

    /** 获取子任务列表，防空 */
    public List<TaskDetail.TaskItemDetail> getTaskItems() {
        if (detail == null || detail.getTaskItems() == null) return List.of();
        return detail.getTaskItems();
    }

    /** 获取命令列表（替代旧的执行单 tickets），防空 */
    public List<TaskDetail.CommandDetail> getCommands() {
        if (detail == null || detail.getCommands() == null) return List.of();
        return detail.getCommands();
    }

    /**
     * 获取所有子任务的执行进度信息：planIndex/issuedIndex/executedIndex。
     * key = 子任务号 taskItemNo（兜底使用 id），value 为三元组 Map。
     */
    public Map<String, Map<String, Integer>> getProgressInfo() {
        Map<String, Map<String, Integer>> result = new LinkedHashMap<>();
        for (TaskDetail.TaskItemDetail item : getTaskItems()) {
            String key = item.getTaskItemNo() != null ? item.getTaskItemNo() : item.getId();
            Map<String, Integer> p = new LinkedHashMap<>();
            p.put("planIndex", item.getPlanIndex());
            p.put("issuedIndex", item.getIssuedIndex());
            p.put("executedIndex", item.getExecutedIndex());
            result.put(key, p);
        }
        return result;
    }

    /**
     * 获取依赖信息：preStartTaskNo / preEndTaskNo / parentTaskNo。
     * 返回值不会包含 null value，以便规则方便判空。
     */
    public Map<String, String> getDependencies() {
        Map<String, String> deps = new HashMap<>();
        if (detail == null) return deps;
        if (detail.getPreStartTaskNo() != null) deps.put("preStartTaskNo", detail.getPreStartTaskNo());
        if (detail.getPreEndTaskNo() != null) deps.put("preEndTaskNo", detail.getPreEndTaskNo());
        if (detail.getParentTaskNo() != null) deps.put("parentTaskNo", detail.getParentTaskNo());
        return deps;
    }

    /** 是否存在依赖任务 */
    public boolean hasDependencies() {
        return !getDependencies().isEmpty();
    }

    /**
     * 获取错误信息：优先使用 TaskDetail.errorMessage；
     * 若主任务无错误信息，则尝试从 commands 中聚合 commandResult（FAILED 状态优先）。
     */
    public String getErrorMessage() {
        if (detail != null && detail.getErrorMessage() != null && !detail.getErrorMessage().isBlank()) {
            return detail.getErrorMessage();
        }
        StringBuilder sb = new StringBuilder();
        for (TaskDetail.CommandDetail cmd : getCommands()) {
            String state = cmd.getCommandState();
            String result = cmd.getCommandResult();
            if (result != null && !result.isBlank() && ("FAILED".equals(state) || "CANCELED".equals(state))) {
                if (sb.length() > 0) sb.append("; ");
                sb.append("命令").append(cmd.getCommandNo() != null ? cmd.getCommandNo() : cmd.getId())
                        .append("(").append(state).append("): ").append(result);
            }
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    /** 检查是否有点位冲突 */
    public boolean hasConflicts() {
        return conflicts != null && !conflicts.isEmpty();
    }

    /** 检查是否有向量检索结果 */
    public boolean hasRelevantDocs() {
        return relevantDocs != null && !relevantDocs.isEmpty();
    }
}
