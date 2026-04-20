package cn.aimstek.loong.aidiag.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SysLogDetail {
    private String wmsTaskNo;
    private String tasItemNo;
    private String ticketNo;
    private String message;
    private Integer logType;
    private LocalDateTime createTime;

    public String toLogLine() {
        String time = createTime != null ? createTime.toString() : "unknown";
        String prefix = "";
        if (tasItemNo != null && !tasItemNo.isEmpty()) {
            prefix += "[子任务:" + tasItemNo + "]";
        }
        if (ticketNo != null && !ticketNo.isEmpty()) {
            prefix += "[执行单:" + ticketNo + "]";
        }
        return time + " " + prefix + " " + (message != null ? message : "");
    }
}
