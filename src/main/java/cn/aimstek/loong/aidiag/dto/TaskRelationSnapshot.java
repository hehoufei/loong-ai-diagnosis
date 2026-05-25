package cn.aimstek.loong.aidiag.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class TaskRelationSnapshot {
    private String focusTaskId;
    private String directBlockerTaskId;
    private String rootBlockerTaskId;
    private List<String> impactedTaskIds = new ArrayList<>();
    private List<TaskSummary> relatedTasks = new ArrayList<>();
    private List<TaskRelationEdge> dependencyEdges = new ArrayList<>();
    private List<ResourceBottleneck> bottleneckResources = new ArrayList<>();

    @Data
    public static class TaskSummary {
        private String taskId;
        /** 任务号（原 wmsTaskNo） */
        private String taskNo;
        /** 任务状态：WAIT_SPLIT/WAIT_PLAN/RUNNING/SUCCESS/CANCEL/MANUAL_SUCCESS */
        private String taskState;
        private String containerCode;
        /** 起点节点（原 businessFrom） */
        private String startNode;
        /** 终点节点（原 businessTo） */
        private String endNode;
        private String deviceCode;
        private String relationType;
        private String relationReason;
        private String blockageType;
        private String blockageReason;
        private String errorMessage;
    }
}
