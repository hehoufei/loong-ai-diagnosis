package cn.aimstek.loong.aidiag.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 任务详情 DTO，匹配 loong-platform 三级任务模型：Task → TaskItem → Command。
 * 对应表：sc_task / sc_task_item / sc_command。
 */
@Data
public class TaskDetail {
    // === sc_task 主任务 ===
    private String taskId;
    /** 任务号（原 wmsTaskNo） */
    private String taskNo;
    private String taskSource;
    /** 业务类型（原 businessType） */
    private String bizType;
    /** 任务类型：N2N/N2S/S2N/S2S/Node/Storage */
    private String taskType;
    /** 任务状态：WAIT_SPLIT/WAIT_PLAN/RUNNING/SUCCESS/CANCEL/MANUAL_SUCCESS */
    private String taskState;
    private String containerCode;
    /** 起点节点（原 businessFrom） */
    private String startNode;
    /** 终点节点（原 businessTo） */
    private String endNode;
    private String errorMessage;
    private Integer priority;

    // === 显式依赖关系字段 ===
    /** 根任务号 */
    private String rootTaskNo;
    /** 任务组编码 */
    private String groupCode;
    /** 开始依赖的父任务号 */
    private String preStartTaskNo;
    /** 结束依赖的父任务号 */
    private String preEndTaskNo;
    /** 父任务号 */
    private String parentTaskNo;

    // === 状态/位置/容器 ===
    /** 是否暂停 Y/N */
    private String paused;
    /** 货物当前位置 */
    private String goodsLocation;
    /** 当前移动货物的设备 */
    private String goodsDeviceCode;
    /** 容器信息 JSON */
    private String containerList;
    /** 规划全路径 JSON */
    private String planFullPath;

    // === 时间字段 ===
    private LocalDateTime createTime;
    private LocalDateTime splitTime;
    private LocalDateTime startTime;
    private LocalDateTime finishTime;
    /** 引擎规划时间 */
    private LocalDateTime plannedTime;
    /** 预估开始时间 */
    private LocalDateTime estimatedStartTime;
    /** 预估完成时间 */
    private LocalDateTime estimatedFinishTime;

    // === sc_task_item 子任务列表 ===
    private List<TaskItemDetail> taskItems = new ArrayList<>();

    // === sc_command 命令列表 ===
    private List<CommandDetail> commands = new ArrayList<>();

    @Data
    public static class TaskItemDetail {
        private String id;
        /** 子任务号 */
        private String taskItemNo;
        /** 所属任务号 */
        private String taskNo;
        /** 起点节点（原 startPoint） */
        private String startNode;
        /** 终点节点（原 endPoint） */
        private String endNode;
        private String deviceCode;
        private String deviceType;
        /** 子任务状态：WAIT_SPLIT/WAIT_PLAN/RUNNING/SUCCESS/PAUSED/CANCEL/MANUAL_SUCCESS */
        private String taskItemState;
        /** 前序子任务号 */
        private String preTaskItemNo;
        /** 所有前序子任务号列表（JSON） */
        private String allPreTaskItemNoList;
        /** 任务动作 */
        private String taskAction;
        /** 规划序号 */
        private Integer planIndex;
        /** 已下发序号 */
        private Integer issuedIndex;
        /** 已执行序号 */
        private Integer executedIndex;
        /** 规划全路径（JSON） */
        private String planFullPath;
        private LocalDateTime startTime;
        private LocalDateTime finishTime;
        private LocalDateTime createTime;
    }

    @Data
    public static class CommandDetail {
        private String id;
        /** 命令号 */
        private String commandNo;
        /** 所属子任务号（原 taskItemId） */
        private String taskItemNo;
        /** 命令类型（原 function） */
        private String commandType;
        /** 命令明细 */
        private String commandDetail;
        /** 命令结果 */
        private String commandResult;
        /** 起点节点（原 startPoint） */
        private String startNode;
        /** 终点节点（原 endPoint） */
        private String endNode;
        private String deviceCode;
        private String deviceType;
        /** 命令状态：CREATED/ISSUING/ISSUED/RUNNING/SUCCESS/MANUAL_SUCCESS/FAILED/CANCELED */
        private String commandState;
        /** 上报状态 */
        private String reportState;
        /** PLC 任务号（原 plcTaskId） */
        private String plcTaskNo;
        /** 错误码 */
        private String errorCode;
        /** 错误信息 */
        private String errorMsg;
        /** 设备执行结果 */
        private String execResult;
        /** 设备 ack 确认结果 */
        private String ackResult;
        /** 设备 ack 确认明细 */
        private String ackDetail;
        private LocalDateTime startTime;
        private LocalDateTime finishTime;
        private LocalDateTime createTime;
    }
}
