package cn.aimstek.loong.aidiag.client.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对应 sc_command 表。
 */
@Data
public class PlatformCommand {

    private String taskItemNo;
    private String commandNo;
    /** PLC 任务号，SENT 后由 PLC 回填；为空表示 PLC 尚未响应 */
    private String plcTaskNo;
    private String deviceCode;
    private String deviceType;
    private String commandType;
    /** WAIT / SENT / ACKED / SUCCESS / FAILED / TIMEOUT */
    private String commandState;
    private String commandDetail;
    private String commandResult;
    private LocalDateTime startTime;
    private LocalDateTime finishTime;
}
