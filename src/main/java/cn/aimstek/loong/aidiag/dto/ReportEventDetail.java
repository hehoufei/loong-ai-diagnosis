package cn.aimstek.loong.aidiag.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 任务上报事件明细 DTO，对应 loong-platform 的 sc_report_detail 表。
 * 用于在诊断界面展示任务的状态变更/上报历史，辅助根因分析。
 */
@Data
public class ReportEventDetail {

    /** 任务号 */
    private String taskNo;
    /** 子任务号 */
    private String taskItemNo;
    /** 指令号 */
    private String commandNo;
    /** 上报状态，如 WAIT_REPORT / SUCCESS / FAIL */
    private String reportState;
    /** 事件类型，如 TASK_REPORT / COMMAND_REPORT / SIGNAL_REPORT */
    private String eventType;
    /** 事件具体信息，如任务/指令的当前状态字符串 */
    private String eventDetail;
    /** 事件产生时间 */
    private LocalDateTime produceTime;
    /** 请求体 JSON 字符串（调试用） */
    private String payload;
    /** 响应体 JSON 字符串（调试用） */
    private String response;
    /** 重试次数 */
    private Integer retryTimes;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 转为单行日志格式，便于直接拼接进 LLM 上下文或纯文本展示。
     */
    public String toLogLine() {
        StringBuilder sb = new StringBuilder();
        if (produceTime != null) {
            sb.append(produceTime.format(FMT));
        }
        sb.append(" [").append(eventType != null ? eventType : "UNKNOWN").append("]");
        if (taskItemNo != null && !taskItemNo.isEmpty()) {
            sb.append(" [子任务:").append(taskItemNo).append("]");
        }
        if (commandNo != null && !commandNo.isEmpty()) {
            sb.append(" [指令:").append(commandNo).append("]");
        }
        sb.append(" 状态:").append(reportState != null ? reportState : "-");
        if (eventDetail != null && !eventDetail.isEmpty()) {
            sb.append(" 详情:").append(eventDetail);
        }
        if (retryTimes != null && retryTimes > 0) {
            sb.append(" 重试:").append(retryTimes);
        }
        return sb.toString();
    }
}
