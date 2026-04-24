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
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class JdbcTaskClient implements TaskClient {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public TaskDetail getTaskDetail(String taskId, String env) {
        // 先查主任务（支持按 id 或 wms_task_no 查询）
        List<TaskDetail> tasks = jdbcTemplate.query(
            "SELECT id, wms_task_no, task_source, business_type, task_type, task_state, handle_state, " +
            "container_code, business_from, definite_from, business_to, definite_to, error_message, " +
            "priority, create_time, definite_time, split_time, start_time, finish_time " +
            "FROM tas_task WHERE id = ? OR wms_task_no = ? ORDER BY create_time DESC LIMIT 1",
            (rs, rowNum) -> mapTask(rs), taskId, taskId);

        if (tasks.isEmpty()) {
            // 再查历史表
            tasks = jdbcTemplate.query(
                "SELECT id, wms_task_no, task_source, business_type, task_type, task_state, handle_state, " +
                "container_code, business_from, definite_from, business_to, definite_to, error_message, " +
                "priority, create_time, definite_time, split_time, start_time, finish_time " +
                "FROM his_tas_task WHERE id = ? OR wms_task_no = ? ORDER BY create_time DESC LIMIT 1",
                (rs, rowNum) -> mapTask(rs), taskId, taskId);
        }

        if (tasks.isEmpty()) {
            throw new AiDiagnosisException("TASK_NOT_FOUND", "未找到任务: " + taskId);
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

        Map<String, TaskDetail> related = new LinkedHashMap<>();
        addTasks(related, queryRelatedCurrentTasks(focusTask, effectiveStart, effectiveEnd));
        addTasks(related, queryRelatedHistoryTasks(focusTask, effectiveStart, effectiveEnd));
        related.remove(focusTask.getTaskId());

        List<TaskDetail> result = new ArrayList<>(related.values());
        for (TaskDetail detail : result) {
            detail.setTaskItems(queryTaskItems(detail.getTaskId(), detail.getWmsTaskNo()));
            detail.setTickets(queryTickets(detail.getWmsTaskNo()));
        }
        return result;
    }

    private List<TaskDetail.TaskItemDetail> queryTaskItems(String taskId, String wmsTaskNo) {
        List<TaskDetail.TaskItemDetail> items = jdbcTemplate.query(
            "SELECT id, wms_task_no, start_point, end_point, device_code, device_type, " +
            "task_state, check_status, start_time, finish_time, create_time " +
            "FROM tas_task_item WHERE parent_id = ? OR wms_task_no = ? ORDER BY create_time",
            (rs, rowNum) -> mapTaskItem(rs), taskId, wmsTaskNo);

        if (items.isEmpty()) {
            // 查历史表
            items = jdbcTemplate.query(
                "SELECT id, wms_task_no, start_point, end_point, device_code, device_type, " +
                "task_state, check_status, start_time, finish_time, create_time " +
                "FROM his_tas_task_item WHERE parent_id = ? OR wms_task_no = ? ORDER BY create_time",
                (rs, rowNum) -> mapTaskItem(rs), taskId, wmsTaskNo);
        }
        return items;
    }

    private List<TaskDetail.TicketDetail> queryTickets(String wmsTaskNo) {
        List<TaskDetail.TicketDetail> tickets = jdbcTemplate.query(
            "SELECT t.id, t.task_item_id, t.`function`, t.start_point, t.end_point, t.start_node_num, t.end_node_num, " +
            "t.device_code, t.device_type, t.task_state, t.plc_task_id, t.task_sort, t.start_time, t.finish_time, t.create_time, " +
            "d.device_name " +
            "FROM acs_ticket t LEFT JOIN map_device d ON t.device_code = d.device_code " +
            "WHERE t.wms_task_no = ? ORDER BY t.task_sort, t.create_time",
            (rs, rowNum) -> mapTicket(rs), wmsTaskNo);

        if (tickets.isEmpty()) {
            tickets = jdbcTemplate.query(
                "SELECT t.id, t.task_item_id, t.`function`, t.start_point, t.end_point, t.start_node_num, t.end_node_num, " +
                "t.device_code, t.device_type, t.task_state, t.plc_task_id, t.task_sort, t.start_time, t.finish_time, t.create_time, " +
                "d.device_name " +
                "FROM his_acs_ticket t LEFT JOIN map_device d ON t.device_code = d.device_code " +
                "WHERE t.wms_task_no = ? ORDER BY t.task_sort, t.create_time",
                (rs, rowNum) -> mapTicket(rs), wmsTaskNo);
        }
        return tickets;
    }

    private List<TaskDetail> queryRelatedCurrentTasks(TaskDetail focusTask, LocalDateTime windowStart, LocalDateTime windowEnd) {
        return jdbcTemplate.query(
                "SELECT DISTINCT id, wms_task_no, task_source, business_type, task_type, task_state, handle_state, " +
                        "container_code, business_from, definite_from, business_to, definite_to, error_message, " +
                        "priority, create_time, definite_time, split_time, start_time, finish_time " +
                        "FROM tas_task WHERE create_time BETWEEN ? AND ? AND (id = ? OR wms_task_no = ? OR container_code = ? " +
                        "OR business_from = ? OR definite_from = ? OR business_to = ? OR definite_to = ?) ORDER BY create_time DESC LIMIT 20",
                (rs, rowNum) -> mapTask(rs),
                Timestamp.valueOf(windowStart), Timestamp.valueOf(windowEnd),
                focusTask.getTaskId(), focusTask.getWmsTaskNo(), focusTask.getContainerCode(),
                focusTask.getBusinessFrom(), focusTask.getDefiniteFrom(), focusTask.getBusinessTo(), focusTask.getDefiniteTo());
    }

    private List<TaskDetail> queryRelatedHistoryTasks(TaskDetail focusTask, LocalDateTime windowStart, LocalDateTime windowEnd) {
        return jdbcTemplate.query(
                "SELECT DISTINCT id, wms_task_no, task_source, business_type, task_type, task_state, handle_state, " +
                        "container_code, business_from, definite_from, business_to, definite_to, error_message, " +
                        "priority, create_time, definite_time, split_time, start_time, finish_time " +
                        "FROM his_tas_task WHERE create_time BETWEEN ? AND ? AND (id = ? OR wms_task_no = ? OR container_code = ? " +
                        "OR business_from = ? OR definite_from = ? OR business_to = ? OR definite_to = ?) ORDER BY create_time DESC LIMIT 20",
                (rs, rowNum) -> mapTask(rs),
                Timestamp.valueOf(windowStart), Timestamp.valueOf(windowEnd),
                focusTask.getTaskId(), focusTask.getWmsTaskNo(), focusTask.getContainerCode(),
                focusTask.getBusinessFrom(), focusTask.getDefiniteFrom(), focusTask.getBusinessTo(), focusTask.getDefiniteTo());
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
