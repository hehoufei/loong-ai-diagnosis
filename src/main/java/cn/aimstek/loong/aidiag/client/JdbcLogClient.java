package cn.aimstek.loong.aidiag.client;

import cn.aimstek.loong.aidiag.dto.ReportEventDetail;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class JdbcLogClient implements LogClient {

    private final JdbcTemplate jdbcTemplate;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public List<String> queryLogs(String taskNo, String env) {
        if (taskNo == null || taskNo.isBlank()) {
            return new ArrayList<>();
        }
        return jdbcTemplate.query(
            "SELECT command_no, task_no, task_item_no, device_code, device_type, " +
            "command_state, start_time, finish_time, create_time " +
            "FROM sc_command WHERE task_no = ? ORDER BY create_time LIMIT 200",
            (rs, rowNum) -> {
                String commandNo = rs.getString("command_no");
                String taskItemNo = rs.getString("task_item_no");
                String deviceCode = rs.getString("device_code");
                String deviceType = rs.getString("device_type");
                String commandState = rs.getString("command_state");
                Timestamp startTs = rs.getTimestamp("start_time");
                Timestamp finishTs = rs.getTimestamp("finish_time");
                Timestamp createTs = rs.getTimestamp("create_time");

                LocalDateTime time = createTs != null ? createTs.toLocalDateTime()
                        : (startTs != null ? startTs.toLocalDateTime() : null);

                StringBuilder sb = new StringBuilder();
                if (time != null) {
                    sb.append(time.format(FMT));
                }
                if (deviceCode != null && !deviceCode.isEmpty()) {
                    sb.append(" [设备:").append(deviceCode);
                    if (deviceType != null && !deviceType.isEmpty()) {
                        sb.append("/").append(deviceType);
                    }
                    sb.append("]");
                }
                if (taskItemNo != null && !taskItemNo.isEmpty()) {
                    sb.append("[子任务:").append(taskItemNo).append("]");
                }
                if (commandNo != null && !commandNo.isEmpty()) {
                    sb.append("[指令:").append(commandNo).append("]");
                }
                if (commandState != null) {
                    sb.append(" 状态: ").append(commandState);
                }
                if (startTs != null) {
                    sb.append(" 开始: ").append(startTs.toLocalDateTime().format(FMT));
                }
                if (finishTs != null) {
                    sb.append(" 完成: ").append(finishTs.toLocalDateTime().format(FMT));
                }
                return sb.toString();
            }, taskNo);
    }

    @Override
    public List<ReportEventDetail> queryReportEvents(String taskNo, String env) {
        if (taskNo == null || taskNo.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return jdbcTemplate.query(
                "SELECT task_no, task_item_no, command_no, report_state, event_type, " +
                "event_detail, produce_time, payload, response, retry_times " +
                "FROM sc_report_detail WHERE task_no = ? " +
                "ORDER BY produce_time DESC LIMIT 50",
                (rs, rowNum) -> {
                    ReportEventDetail e = new ReportEventDetail();
                    e.setTaskNo(rs.getString("task_no"));
                    e.setTaskItemNo(rs.getString("task_item_no"));
                    e.setCommandNo(rs.getString("command_no"));
                    e.setReportState(rs.getString("report_state"));
                    e.setEventType(rs.getString("event_type"));
                    e.setEventDetail(rs.getString("event_detail"));
                    Timestamp pt = rs.getTimestamp("produce_time");
                    e.setProduceTime(pt != null ? pt.toLocalDateTime() : null);
                    e.setPayload(rs.getString("payload"));
                    e.setResponse(rs.getString("response"));
                    int rt = rs.getInt("retry_times");
                    e.setRetryTimes(rs.wasNull() ? null : rt);
                    return e;
                }, taskNo);
        } catch (Exception ex) {
            log.warn("查询sc_report_detail失败 taskNo={}: {}", taskNo, ex.getMessage());
            return new ArrayList<>();
        }
    }
}
