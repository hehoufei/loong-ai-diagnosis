package cn.aimstek.loong.aidiag.client;

import cn.aimstek.loong.aidiag.dto.TaskDetail;
import cn.aimstek.loong.aidiag.exception.AiDiagnosisException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class JdbcTaskClient implements TaskClient {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public TaskDetail getTaskDetail(String taskId, String env) {
        List<TaskDetail> tasks = jdbcTemplate.query(
            "SELECT id, wms_task_no, task_source, business_type, task_type, task_state, handle_state, " +
            "container_code, business_from, definite_from, business_to, definite_to, error_message, " +
            "priority, create_time, definite_time, split_time, start_time, finish_time " +
            "FROM tas_task WHERE id = ? OR wms_task_no = ? ORDER BY create_time DESC LIMIT 1",
            (rs, rowNum) -> mapTask(rs), taskId, taskId);

        if (tasks.isEmpty()) {
            throw new AiDiagnosisException("TASK_NOT_FOUND", "未找到当前活动任务: " + taskId);
        }

        TaskDetail detail = tasks.get(0);
        // 查子任务
        detail.setTaskItems(queryTaskItems(detail.getTaskId(), detail.getWmsTaskNo()));
        // 查执行单
        detail.setTickets(queryTickets(detail.getWmsTaskNo()));
        return detail;
    }

    @Override
    public List<TaskDetail> findRelatedTasks(TaskDetail focusTask, LocalDateTime windowStart, LocalDateTime windowEnd, String env) {
        if (focusTask == null) {
            return List.of();
        }

        LocalDateTime effectiveStart = windowStart != null
                ? windowStart
                : (focusTask.getCreateTime() != null ? focusTask.getCreateTime().minusMinutes(30) : LocalDateTime.now().minusMinutes(30));
        LocalDateTime effectiveEnd = windowEnd != null ? windowEnd : LocalDateTime.now().plusMinutes(5);

        Set<String> deviceCodes = collectDeviceCodes(focusTask);
        Set<String> pointCodes = collectPointCodes(focusTask);

        Map<String, TaskDetail> related = new LinkedHashMap<>();
        addTasks(related, queryRelatedCurrentTasks(focusTask, effectiveStart, effectiveEnd, deviceCodes, pointCodes));
        addTasks(related, queryTasksByTaskItemDeviceCodes(deviceCodes, effectiveStart, effectiveEnd));
        addTasks(related, queryTasksByTicketResources(deviceCodes, pointCodes, effectiveStart, effectiveEnd));
        related.remove(focusTask.getTaskId());

        List<TaskDetail> result = new ArrayList<>(related.values());
        for (TaskDetail detail : result) {
            detail.setTaskItems(queryTaskItems(detail.getTaskId(), detail.getWmsTaskNo()));
            detail.setTickets(queryTickets(detail.getWmsTaskNo()));
        }
        return result;
    }

    private List<TaskDetail.TaskItemDetail> queryTaskItems(String taskId, String wmsTaskNo) {
        return jdbcTemplate.query(
            "SELECT id, wms_task_no, start_point, end_point, device_code, device_type, " +
            "task_state, check_status, start_time, finish_time, create_time " +
            "FROM tas_task_item WHERE parent_id = ? OR wms_task_no = ? ORDER BY create_time",
            (rs, rowNum) -> mapTaskItem(rs), taskId, wmsTaskNo);
    }

    private List<TaskDetail.TicketDetail> queryTickets(String wmsTaskNo) {
        return jdbcTemplate.query(
            "SELECT t.id, t.task_item_id, t.`function`, t.start_point, t.end_point, t.start_node_num, t.end_node_num, " +
            "t.device_code, t.device_type, t.task_state, t.plc_task_id, t.task_sort, t.start_time, t.finish_time, t.create_time, " +
            "d.device_name " +
            "FROM acs_ticket t LEFT JOIN map_device d ON t.device_code = d.device_code " +
            "WHERE t.wms_task_no = ? ORDER BY t.task_sort, t.create_time",
            (rs, rowNum) -> mapTicket(rs), wmsTaskNo);
    }

    private List<TaskDetail> queryRelatedCurrentTasks(TaskDetail focusTask, LocalDateTime windowStart, LocalDateTime windowEnd,
                                                       Set<String> deviceCodes, Set<String> pointCodes) {
        List<Object> params = new ArrayList<>();
        String sql = buildRelatedTaskSql(focusTask, windowStart, windowEnd, deviceCodes, pointCodes, params);
        return jdbcTemplate.query(sql, (rs, rowNum) -> mapTask(rs), params.toArray());
    }

    private List<TaskDetail> queryTasksByTaskItemDeviceCodes(Set<String> deviceCodes, LocalDateTime windowStart,
                                                             LocalDateTime windowEnd) {
        if (deviceCodes.isEmpty()) {
            return List.of();
        }
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT DISTINCT t.id, t.wms_task_no, t.task_source, t.business_type, t.task_type, t.task_state, t.handle_state, ")
                .append("t.container_code, t.business_from, t.definite_from, t.business_to, t.definite_to, t.error_message, ")
                .append("t.priority, t.create_time, t.definite_time, t.split_time, t.start_time, t.finish_time ")
                .append("FROM tas_task t ")
                .append("JOIN tas_task_item i ON i.parent_id = t.id OR i.wms_task_no = t.wms_task_no ")
                .append("WHERE t.create_time BETWEEN ? AND ? AND i.device_code IN (")
                .append(placeholders(deviceCodes.size())).append(") ")
                .append("ORDER BY t.create_time DESC LIMIT 20");
        params.add(Timestamp.valueOf(windowStart));
        params.add(Timestamp.valueOf(windowEnd));
        params.addAll(deviceCodes);
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> mapTask(rs), params.toArray());
    }

    private List<TaskDetail> queryTasksByTicketResources(Set<String> deviceCodes, Set<String> pointCodes,
                                                         LocalDateTime windowStart, LocalDateTime windowEnd) {
        if (deviceCodes.isEmpty() && pointCodes.isEmpty()) {
            return List.of();
        }
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT DISTINCT t.id, t.wms_task_no, t.task_source, t.business_type, t.task_type, t.task_state, t.handle_state, ")
                .append("t.container_code, t.business_from, t.definite_from, t.business_to, t.definite_to, t.error_message, ")
                .append("t.priority, t.create_time, t.definite_time, t.split_time, t.start_time, t.finish_time ")
                .append("FROM tas_task t ")
                .append("JOIN acs_ticket tk ON tk.wms_task_no = t.wms_task_no ")
                .append("WHERE t.create_time BETWEEN ? AND ?");
        params.add(Timestamp.valueOf(windowStart));
        params.add(Timestamp.valueOf(windowEnd));

        List<String> clauses = new ArrayList<>();
        if (!deviceCodes.isEmpty()) {
            clauses.add("tk.device_code IN (" + placeholders(deviceCodes.size()) + ")");
            params.addAll(deviceCodes);
        }
        if (!pointCodes.isEmpty()) {
            String pointClause = "(tk.start_point IN (" + placeholders(pointCodes.size()) + ") OR tk.end_point IN (" + placeholders(pointCodes.size()) + "))";
            clauses.add(pointClause);
            params.addAll(pointCodes);
            params.addAll(pointCodes);
        }
        sql.append(" AND (").append(String.join(" OR ", clauses)).append(") ")
                .append("ORDER BY t.create_time DESC LIMIT 20");
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> mapTask(rs), params.toArray());
    }

    private String buildRelatedTaskSql(TaskDetail focusTask, LocalDateTime windowStart,
                                       LocalDateTime windowEnd, Set<String> deviceCodes, Set<String> pointCodes,
                                       List<Object> params) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT DISTINCT id, wms_task_no, task_source, business_type, task_type, task_state, handle_state, ")
                .append("container_code, business_from, definite_from, business_to, definite_to, error_message, ")
                .append("priority, create_time, definite_time, split_time, start_time, finish_time ")
                .append("FROM tas_task WHERE create_time BETWEEN ? AND ?");
        params.add(Timestamp.valueOf(windowStart));
        params.add(Timestamp.valueOf(windowEnd));

        List<String> clauses = new ArrayList<>();
        if (focusTask.getTaskId() != null) {
            clauses.add("id = ?");
            params.add(focusTask.getTaskId());
        }
        if (focusTask.getWmsTaskNo() != null) {
            clauses.add("wms_task_no = ?");
            params.add(focusTask.getWmsTaskNo());
        }
        if (focusTask.getContainerCode() != null) {
            clauses.add("container_code = ?");
            params.add(focusTask.getContainerCode());
        }
        appendPointClauses(clauses, params, "business_from", pointCodes);
        appendPointClauses(clauses, params, "definite_from", pointCodes);
        appendPointClauses(clauses, params, "business_to", pointCodes);
        appendPointClauses(clauses, params, "definite_to", pointCodes);
        if (!deviceCodes.isEmpty()) {
            clauses.add("id IN (SELECT DISTINCT parent_id FROM tas_task_item WHERE device_code IN (" + placeholders(deviceCodes.size()) + "))");
            params.addAll(deviceCodes);
        }

        if (clauses.isEmpty()) {
            sql.append(" ORDER BY create_time DESC LIMIT 20");
            return sql.toString();
        }

        sql.append(" AND (").append(String.join(" OR ", clauses)).append(") ")
                .append("ORDER BY create_time DESC LIMIT 20");
        return sql.toString();
    }

    private void appendPointClauses(List<String> clauses, List<Object> params, String column, Set<String> pointCodes) {
        if (pointCodes.isEmpty()) {
            return;
        }
        clauses.add(column + " IN (" + placeholders(pointCodes.size()) + ")");
        params.addAll(pointCodes);
    }

    private Set<String> collectDeviceCodes(TaskDetail task) {
        Set<String> deviceCodes = new LinkedHashSet<>();
        if (task == null) {
            return deviceCodes;
        }
        if (task.getTaskItems() != null) {
            for (TaskDetail.TaskItemDetail item : task.getTaskItems()) {
                if (item.getDeviceCode() != null && !item.getDeviceCode().isBlank()) {
                    deviceCodes.add(item.getDeviceCode());
                }
            }
        }
        if (task.getTickets() != null) {
            for (TaskDetail.TicketDetail ticket : task.getTickets()) {
                if (ticket.getDeviceCode() != null && !ticket.getDeviceCode().isBlank()) {
                    deviceCodes.add(ticket.getDeviceCode());
                }
            }
        }
        return deviceCodes;
    }

    private Set<String> collectPointCodes(TaskDetail task) {
        Set<String> pointCodes = new LinkedHashSet<>();
        if (task == null) {
            return pointCodes;
        }
        addIfHasText(pointCodes, task.getBusinessFrom());
        addIfHasText(pointCodes, task.getDefiniteFrom());
        addIfHasText(pointCodes, task.getBusinessTo());
        addIfHasText(pointCodes, task.getDefiniteTo());
        if (task.getTaskItems() != null) {
            for (TaskDetail.TaskItemDetail item : task.getTaskItems()) {
                addIfHasText(pointCodes, item.getStartPoint());
                addIfHasText(pointCodes, item.getEndPoint());
            }
        }
        if (task.getTickets() != null) {
            for (TaskDetail.TicketDetail ticket : task.getTickets()) {
                addIfHasText(pointCodes, ticket.getStartPoint());
                addIfHasText(pointCodes, ticket.getEndPoint());
            }
        }
        return pointCodes;
    }

    private void addIfHasText(Set<String> values, String value) {
        if (value != null && !value.isBlank()) {
            values.add(value);
        }
    }

    private String placeholders(int size) {
        return String.join(",", java.util.Collections.nCopies(size, "?"));
    }

    private void addTasks(Map<String, TaskDetail> target, List<TaskDetail> tasks) {
        for (TaskDetail detail : tasks) {
            if (detail != null && detail.getTaskId() != null) {
                target.putIfAbsent(detail.getTaskId(), detail);
            }
        }
    }

    private TaskDetail mapTask(ResultSet rs) throws SQLException {
        TaskDetail d = new TaskDetail();
        d.setTaskId(rs.getString("id"));
        d.setWmsTaskNo(rs.getString("wms_task_no"));
        d.setTaskSource(rs.getString("task_source"));
        d.setBusinessType(rs.getString("business_type"));
        d.setTaskType(rs.getObject("task_type", Integer.class));
        d.setTaskState(rs.getString("task_state"));
        d.setHandleState(rs.getString("handle_state"));
        d.setContainerCode(rs.getString("container_code"));
        d.setBusinessFrom(rs.getString("business_from"));
        d.setDefiniteFrom(rs.getString("definite_from"));
        d.setBusinessTo(rs.getString("business_to"));
        d.setDefiniteTo(rs.getString("definite_to"));
        d.setErrorMessage(rs.getString("error_message"));
        d.setPriority(rs.getObject("priority", Integer.class));
        d.setCreateTime(toLocalDateTime(rs.getTimestamp("create_time")));
        d.setDefiniteTime(toLocalDateTime(rs.getTimestamp("definite_time")));
        d.setSplitTime(toLocalDateTime(rs.getTimestamp("split_time")));
        d.setStartTime(toLocalDateTime(rs.getTimestamp("start_time")));
        d.setFinishTime(toLocalDateTime(rs.getTimestamp("finish_time")));
        return d;
    }

    private TaskDetail.TaskItemDetail mapTaskItem(ResultSet rs) throws SQLException {
        TaskDetail.TaskItemDetail item = new TaskDetail.TaskItemDetail();
        item.setId(rs.getString("id"));
        item.setWmsTaskNo(rs.getString("wms_task_no"));
        item.setStartPoint(rs.getString("start_point"));
        item.setEndPoint(rs.getString("end_point"));
        item.setDeviceCode(rs.getString("device_code"));
        item.setDeviceType(rs.getString("device_type"));
        item.setTaskState(rs.getString("task_state"));
        item.setCheckStatus(rs.getString("check_status"));
        item.setStartTime(toLocalDateTime(rs.getTimestamp("start_time")));
        item.setFinishTime(toLocalDateTime(rs.getTimestamp("finish_time")));
        item.setCreateTime(toLocalDateTime(rs.getTimestamp("create_time")));
        return item;
    }

    private TaskDetail.TicketDetail mapTicket(ResultSet rs) throws SQLException {
        TaskDetail.TicketDetail t = new TaskDetail.TicketDetail();
        t.setId(rs.getString("id"));
        t.setTaskItemId(rs.getString("task_item_id"));
        t.setFunction(rs.getString("function"));
        t.setStartPoint(rs.getString("start_point"));
        t.setEndPoint(rs.getString("end_point"));
        t.setStartNodeNum(rs.getString("start_node_num"));
        t.setEndNodeNum(rs.getString("end_node_num"));
        t.setDeviceCode(rs.getString("device_code"));
//        t.setDeviceName(rs.getString("device_name"));
        t.setDeviceType(rs.getString("device_type"));
        t.setTaskState(rs.getString("task_state"));
        t.setPlcTaskId(rs.getString("plc_task_id"));
        t.setTaskSort(rs.getObject("task_sort", Integer.class));
        t.setStartTime(toLocalDateTime(rs.getTimestamp("start_time")));
        t.setFinishTime(toLocalDateTime(rs.getTimestamp("finish_time")));
        t.setCreateTime(toLocalDateTime(rs.getTimestamp("create_time")));
        return t;
    }

    private LocalDateTime toLocalDateTime(Timestamp ts) {
        return ts != null ? ts.toLocalDateTime() : null;
    }
}
