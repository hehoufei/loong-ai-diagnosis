package cn.aimstek.loong.aidiag.client.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 对应 sc_task_item 表，含嵌套 commands。
 */
@Data
public class PlatformTaskItem {

    private String taskNo;
    private String taskItemNo;
    private String preTaskItemNo;
    private String deviceCode;
    private String deviceType;
    /** WAIT_SPLIT / WAIT_PLAN / RUNNING / SUCCESS / CANCEL */
    private String taskItemState;
    private String taskAction;
    /** ENGINE / BIZ / SYSTEM */
    private String scheduleChannel;
    /** YES / NO */
    private String paused;
    private String startNode;
    private String endNode;
    private LocalDateTime startTime;
    private LocalDateTime finishTime;

    private List<PlatformCommand> commands = new ArrayList<>();
}
