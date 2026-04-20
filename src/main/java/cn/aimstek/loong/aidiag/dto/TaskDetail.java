package cn.aimstek.loong.aidiag.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class TaskDetail {
    // === tas_task 主任务 ===
    private String taskId;
    private String wmsTaskNo;
    private String taskSource;
    private String businessType;
    private Integer taskType;
    private String taskState;
    private String handleState;
    private String containerCode;
    private String businessFrom;
    private String definiteFrom;
    private String businessTo;
    private String definiteTo;
    private String errorMessage;
    private Integer priority;
    private LocalDateTime createTime;
    private LocalDateTime definiteTime;
    private LocalDateTime splitTime;
    private LocalDateTime startTime;
    private LocalDateTime finishTime;

    // === tas_task_item 子任务列表 ===
    private List<TaskItemDetail> taskItems = new ArrayList<>();

    // === acs_ticket 执行单列表 ===
    private List<TicketDetail> tickets = new ArrayList<>();

    @Data
    public static class TaskItemDetail {
        private String id;
        private String wmsTaskNo;
        private String startPoint;
        private String endPoint;
        private String deviceCode;
        private String deviceType;
        private String taskState;
        private String checkStatus;
        private LocalDateTime startTime;
        private LocalDateTime finishTime;
        private LocalDateTime createTime;
    }

    @Data
    public static class TicketDetail {
        private String id;
        private String taskItemId;
        private String function;
        private String startPoint;
        private String endPoint;
        private String deviceCode;
        private String deviceName;
        private String deviceType;
        private String taskState;
        private String plcTaskId;
        private Integer taskSort;
        private String startNodeNum;
        private String endNodeNum;
        private LocalDateTime startTime;
        private LocalDateTime finishTime;
        private LocalDateTime createTime;
    }
}
