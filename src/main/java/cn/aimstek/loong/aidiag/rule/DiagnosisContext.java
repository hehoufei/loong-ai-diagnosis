package cn.aimstek.loong.aidiag.rule;

import cn.aimstek.loong.aidiag.dto.TaskDetail;
import cn.aimstek.loong.aidiag.dto.PointConflict;
import lombok.Data;
import lombok.Builder;

import java.util.List;
import java.util.ArrayList;

/**
 * 诊断上下文，封装规则执行所需的所有数据。
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

    // ====== 便捷访问方法 ======

    /** 获取主任务的 handleState，防空 */
    public String getHandleState() {
        return detail != null ? detail.getHandleState() : null;
    }

    /** 获取子任务列表，防空 */
    public List<TaskDetail.TaskItemDetail> getTaskItems() {
        if (detail == null || detail.getTaskItems() == null) return List.of();
        return detail.getTaskItems();
    }

    /** 获取执行单列表，防空 */
    public List<TaskDetail.TicketDetail> getTickets() {
        if (detail == null || detail.getTickets() == null) return List.of();
        return detail.getTickets();
    }

    /** 获取错误信息 */
    public String getErrorMessage() {
        return detail != null ? detail.getErrorMessage() : null;
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
