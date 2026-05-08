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
        private String wmsTaskNo;
        private String taskState;
        private String handleState;
        private String containerCode;
        private String businessFrom;
        private String businessTo;
        private String deviceCode;
        private String relationType;
        private String relationReason;
        private String blockageType;
        private String blockageReason;
        private String errorMessage;
    }
}
