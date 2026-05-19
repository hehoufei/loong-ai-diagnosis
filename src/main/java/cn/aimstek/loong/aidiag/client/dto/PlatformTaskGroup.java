package cn.aimstek.loong.aidiag.client.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 对应 sc_task_group_info 表。
 */
@Data
public class PlatformTaskGroup {

    private String groupCode;
    private String groupType;
    private Integer mainSize;
    private Integer subSize;
    /** FINISH / NOT_FINISH */
    private String splitFinish;
    private LocalDateTime splitFinishTime;
    private List<PlatformTask> tasksInGroup = new ArrayList<>();
}
