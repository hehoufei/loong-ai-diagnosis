package cn.aimstek.loong.aidiag.client.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 对应 sc_task 表（约 20 个字段）。
 */
@Data
public class PlatformTask {

    private String taskNo;
    private String rootTaskNo;
    private String parentTaskNo;
    private String groupCode;
    private String groupType;
    /** MAIN / SUB / SYS_ADD */
    private String groupRole;
    private String taskSource;
    private String bizType;
    private String taskType;
    /** WAIT_SPLIT / WAIT_PLAN / RUNNING / SUCCESS / CANCEL */
    private String taskState;
    /** YES / NO */
    private String paused;
    private String startNode;
    private String endNode;
    private Integer bizPriority;
    private LocalDateTime createTime;
    private LocalDateTime plannedTime;
    private LocalDateTime startTime;
    private LocalDateTime finishTime;
    private List<Object> planSegmentList = new ArrayList<>();
    private List<String> planFullPath = new ArrayList<>();
    private List<Object> requiredFunctionList = new ArrayList<>();
    private String errorMessage;
}
