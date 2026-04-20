package cn.aimstek.loong.aidiag.client;

import cn.aimstek.loong.aidiag.dto.SysLogDetail;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class JdbcLogClient implements LogClient {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public List<String> queryLogs(String taskId, String env) {
        List<SysLogDetail> logs = jdbcTemplate.query(
            "SELECT wms_task_no, tas_item_no, ticket_no, message, log_type, create_time " +
            "FROM sys_log WHERE wms_task_no = ? ORDER BY create_time LIMIT 200",
            (rs, rowNum) -> {
                SysLogDetail d = new SysLogDetail();
                d.setWmsTaskNo(rs.getString("wms_task_no"));
                d.setTasItemNo(rs.getString("tas_item_no"));
                d.setTicketNo(rs.getString("ticket_no"));
                d.setMessage(rs.getString("message"));
                d.setLogType(rs.getObject("log_type", Integer.class));
                Timestamp ts = rs.getTimestamp("create_time");
                d.setCreateTime(ts != null ? ts.toLocalDateTime() : null);
                return d;
            }, taskId);

        return logs.stream().map(SysLogDetail::toLogLine).collect(Collectors.toList());
    }
}
