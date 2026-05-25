package cn.aimstek.loong.aidiag.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 事件详情 DTO，匹配 loong-platform 事件模型（替代旧的 SysLogDetail）。
 * 包含设备、任务三级（task/taskItem/command）以及事件类型、错误信息。
 */
@Data
public class EventDetail {
    /** 设备编码 */
    private String deviceCode;
    /** 设备类型 */
    private String deviceType;
    /** 任务号 */
    private String taskNo;
    /** 子任务号 */
    private String taskItemNo;
    /** 命令号 */
    private String commandNo;
    /**
     * 事件类型：CREATED/ISSUED/RUNNING/SUCCESS/FAILED/CANCELED/GOODS_LOCATION_CHANGE
     */
    private String eventType;
    /** 错误码 */
    private String errorCode;
    /** 错误信息 */
    private String errorMsg;
    /** 事件发生时间 */
    private LocalDateTime dateTime;

    /**
     * 渲染为日志行：
     * [time] [device:xxx/type] [task:xxx][item:xxx][cmd:xxx] [eventType] errorCode:errorMsg
     */
    public String toLogLine() {
        StringBuilder sb = new StringBuilder();
        sb.append(dateTime != null ? dateTime.toString() : "unknown");
        if (deviceCode != null && !deviceCode.isEmpty()) {
            sb.append(" [设备:").append(deviceCode);
            if (deviceType != null && !deviceType.isEmpty()) {
                sb.append('/').append(deviceType);
            }
            sb.append(']');
        }
        if (taskNo != null && !taskNo.isEmpty()) {
            sb.append(" [任务:").append(taskNo).append(']');
        }
        if (taskItemNo != null && !taskItemNo.isEmpty()) {
            sb.append(" [子任务:").append(taskItemNo).append(']');
        }
        if (commandNo != null && !commandNo.isEmpty()) {
            sb.append(" [命令:").append(commandNo).append(']');
        }
        if (eventType != null && !eventType.isEmpty()) {
            sb.append(" [").append(eventType).append(']');
        }
        if (errorCode != null && !errorCode.isEmpty()) {
            sb.append(" errorCode=").append(errorCode);
        }
        if (errorMsg != null && !errorMsg.isEmpty()) {
            sb.append(" errorMsg=").append(errorMsg);
        }
        return sb.toString();
    }
}
