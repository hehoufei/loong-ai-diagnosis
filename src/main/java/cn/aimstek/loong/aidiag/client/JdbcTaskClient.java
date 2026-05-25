package cn.aimstek.loong.aidiag.client;

import cn.aimstek.loong.aidiag.dto.CollectedDataInfo;
import cn.aimstek.loong.aidiag.dto.DeviceLockInfo;
import cn.aimstek.loong.aidiag.dto.GoodsStateInfo;
import cn.aimstek.loong.aidiag.dto.TaskDetail;
import cn.aimstek.loong.aidiag.dto.TaskGroupMember;
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

    private static final String TASK_COLUMNS =
            "id, task_no, root_task_no, task_source, biz_type, task_type, task_state, " +
            "paused, start_node, end_node, " +
            "pre_start_task_no, pre_end_task_no, parent_task_no, group_code, " +
            "goods_location, goods_device_code, container_list, plan_full_path, " +
            "biz_priority, planned_time, start_time, finish_time, " +
            "estimated_start_time, estimated_finish_time, create_time";

    @Override
    public TaskDetail getTaskDetail(String taskNo, String env) {
        List<TaskDetail> tasks = jdbcTemplate.query(
            "SELECT " + TASK_COLUMNS + " FROM sc_task " +
            "WHERE id = ? OR task_no = ? ORDER BY create_time DESC LIMIT 1",
            (rs, rowNum) -> mapTask(rs), taskNo, taskNo);

        if (tasks.isEmpty()) {
            throw new AiDiagnosisException("TASK_NOT_FOUND", "未找到当前活动任务: " + taskNo);
        }

        TaskDetail detail = tasks.get(0);
        detail.setTaskItems(queryTaskItems(detail.getTaskNo()));
        detail.setCommands(queryCommands(detail.getTaskNo()));
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

        String rootTaskNo = focusTask.getRootTaskNo();
        String groupCode = focusTask.getGroupCode();
        String parentTaskNo = focusTask.getParentTaskNo();
        String taskNo = focusTask.getTaskNo();

        // 基于显式依赖关系查询：rootTaskNo / groupCode / parentTaskNo / preStart/preEnd / 自身
        String sql = "SELECT DISTINCT " + TASK_COLUMNS + " FROM sc_task t " +
                "WHERE t.create_time BETWEEN ? AND ? " +
                "  AND (t.root_task_no = ? OR t.group_code = ? OR t.parent_task_no = ? " +
                "       OR t.task_no = ? OR t.pre_start_task_no = ? OR t.pre_end_task_no = ?) " +
                "ORDER BY t.create_time DESC LIMIT 20";

        List<TaskDetail> rows = jdbcTemplate.query(sql, (rs, rowNum) -> mapTask(rs),
                Timestamp.valueOf(effectiveStart),
                Timestamp.valueOf(effectiveEnd),
                rootTaskNo, groupCode, parentTaskNo,
                taskNo, taskNo, taskNo);

        Map<String, TaskDetail> related = new LinkedHashMap<>();
        for (TaskDetail t : rows) {
            if (t.getTaskNo() != null) {
                related.putIfAbsent(t.getTaskNo(), t);
            }
        }
        if (taskNo != null) {
            related.remove(taskNo);
        }

        List<TaskDetail> result = new ArrayList<>(related.values());
        for (TaskDetail detail : result) {
            detail.setTaskItems(queryTaskItems(detail.getTaskNo()));
            detail.setCommands(queryCommands(detail.getTaskNo()));
        }
        return result;
    }

    @Override
    public List<TaskDetail.CommandDetail> getCommands(String taskNo, String env) {
        return queryCommands(taskNo);
    }

    private List<TaskDetail.TaskItemDetail> queryTaskItems(String taskNo) {
        if (taskNo == null) {
            return List.of();
        }
        return jdbcTemplate.query(
            "SELECT id, task_no, task_item_no, device_code, task_item_state, " +
            "start_node, end_node, task_action, pre_task_item_no, " +
            "plan_index, issued_index, executed_index, " +
            "plan_full_path, start_time, finish_time, create_time " +
            "FROM sc_task_item WHERE task_no = ? ORDER BY create_time",
            (rs, rowNum) -> mapTaskItem(rs), taskNo);
    }

    private List<TaskDetail.CommandDetail> queryCommands(String taskNo) {
        if (taskNo == null) {
            return List.of();
        }
        return jdbcTemplate.query(
            "SELECT id, task_no, task_item_no, command_no, plc_task_no, " +
            "device_code, device_type, command_state, command_type, " +
            "start_node, end_node, command_detail, command_result, " +
            "report_state, error_code, error_msg, exec_result, ack_result, ack_detail, " +
            "start_time, finish_time, create_time " +
            "FROM sc_command WHERE task_no = ? ORDER BY create_time",
            (rs, rowNum) -> mapCommand(rs), taskNo);
    }

    private TaskDetail mapTask(ResultSet rs) throws SQLException {
        TaskDetail d = new TaskDetail();
        d.setTaskId(rs.getString("id"));
        d.setTaskNo(rs.getString("task_no"));
        d.setRootTaskNo(rs.getString("root_task_no"));
        d.setTaskSource(rs.getString("task_source"));
        d.setBizType(rs.getString("biz_type"));
        d.setTaskType(rs.getString("task_type"));
        d.setTaskState(rs.getString("task_state"));
        d.setPaused(rs.getString("paused"));
        d.setStartNode(rs.getString("start_node"));
        d.setEndNode(rs.getString("end_node"));
        d.setPreStartTaskNo(rs.getString("pre_start_task_no"));
        d.setPreEndTaskNo(rs.getString("pre_end_task_no"));
        d.setParentTaskNo(rs.getString("parent_task_no"));
        d.setGroupCode(rs.getString("group_code"));
        d.setGoodsLocation(rs.getString("goods_location"));
        d.setGoodsDeviceCode(rs.getString("goods_device_code"));
        d.setContainerList(rs.getString("container_list"));
        d.setPlanFullPath(rs.getString("plan_full_path"));
        d.setPriority(rs.getObject("biz_priority", Integer.class));
        d.setPlannedTime(toLocalDateTime(rs.getTimestamp("planned_time")));
        d.setStartTime(toLocalDateTime(rs.getTimestamp("start_time")));
        d.setFinishTime(toLocalDateTime(rs.getTimestamp("finish_time")));
        d.setEstimatedStartTime(toLocalDateTime(rs.getTimestamp("estimated_start_time")));
        d.setEstimatedFinishTime(toLocalDateTime(rs.getTimestamp("estimated_finish_time")));
        d.setCreateTime(toLocalDateTime(rs.getTimestamp("create_time")));
        return d;
    }

    private TaskDetail.TaskItemDetail mapTaskItem(ResultSet rs) throws SQLException {
        TaskDetail.TaskItemDetail item = new TaskDetail.TaskItemDetail();
        item.setId(rs.getString("id"));
        item.setTaskNo(rs.getString("task_no"));
        item.setTaskItemNo(rs.getString("task_item_no"));
        item.setDeviceCode(rs.getString("device_code"));
        item.setTaskItemState(rs.getString("task_item_state"));
        item.setStartNode(rs.getString("start_node"));
        item.setEndNode(rs.getString("end_node"));
        item.setTaskAction(rs.getString("task_action"));
        item.setPreTaskItemNo(rs.getString("pre_task_item_no"));
        item.setPlanIndex(rs.getObject("plan_index", Integer.class));
        item.setIssuedIndex(rs.getObject("issued_index", Integer.class));
        item.setExecutedIndex(rs.getObject("executed_index", Integer.class));
        item.setPlanFullPath(rs.getString("plan_full_path"));
        item.setStartTime(toLocalDateTime(rs.getTimestamp("start_time")));
        item.setFinishTime(toLocalDateTime(rs.getTimestamp("finish_time")));
        item.setCreateTime(toLocalDateTime(rs.getTimestamp("create_time")));
        return item;
    }

    private TaskDetail.CommandDetail mapCommand(ResultSet rs) throws SQLException {
        TaskDetail.CommandDetail c = new TaskDetail.CommandDetail();
        c.setId(rs.getString("id"));
        c.setTaskItemNo(rs.getString("task_item_no"));
        c.setCommandNo(rs.getString("command_no"));
        c.setPlcTaskNo(rs.getString("plc_task_no"));
        c.setDeviceCode(rs.getString("device_code"));
        c.setDeviceType(rs.getString("device_type"));
        c.setCommandState(rs.getString("command_state"));
        c.setCommandType(rs.getString("command_type"));
        c.setStartNode(rs.getString("start_node"));
        c.setEndNode(rs.getString("end_node"));
        c.setCommandDetail(rs.getString("command_detail"));
        c.setCommandResult(rs.getString("command_result"));
        c.setReportState(rs.getString("report_state"));
        c.setErrorCode(safeGetString(rs, "error_code"));
        c.setErrorMsg(safeGetString(rs, "error_msg"));
        c.setExecResult(safeGetString(rs, "exec_result"));
        c.setAckResult(safeGetString(rs, "ack_result"));
        c.setAckDetail(safeGetString(rs, "ack_detail"));
        c.setStartTime(toLocalDateTime(rs.getTimestamp("start_time")));
        c.setFinishTime(toLocalDateTime(rs.getTimestamp("finish_time")));
        c.setCreateTime(toLocalDateTime(rs.getTimestamp("create_time")));
        return c;
    }

    private LocalDateTime toLocalDateTime(Timestamp ts) {
        return ts != null ? ts.toLocalDateTime() : null;
    }

    /**
     * 安全获取字段值，列不存在时返回 null。
     * 用于兼容不同版本 loong-platform 的表结构差异。
     */
    private String safeGetString(ResultSet rs, String columnName) {
        try {
            return rs.getString(columnName);
        } catch (SQLException e) {
            return null;
        }
    }

    // ========================= 新增诊断数据源查询 =========================

    /**
     * 查询设备锁定状态。
     * 对应 sc_device_lock 表，记录设备被占用/锁定的任务上下文。
     */
    @Override
    public List<DeviceLockInfo> queryDeviceLocks(String deviceCode) {
        if (deviceCode == null || deviceCode.isBlank()) {
            return List.of();
        }
        try {
            return jdbcTemplate.query(
                "SELECT id, device_code, task_no, task_item_no, command_no, lock_state, " +
                "update_time, create_time " +
                "FROM sc_device_lock WHERE device_code = ? " +
                "ORDER BY update_time DESC LIMIT 20",
                (rs, rowNum) -> {
                    DeviceLockInfo info = new DeviceLockInfo();
                    info.setId(rs.getObject("id", Long.class));
                    info.setDeviceCode(rs.getString("device_code"));
                    info.setTaskNo(rs.getString("task_no"));
                    info.setTaskItemNo(rs.getString("task_item_no"));
                    info.setCommandNo(rs.getString("command_no"));
                    info.setLockState(rs.getString("lock_state"));
                    Timestamp ts = rs.getTimestamp("update_time");
                    if (ts == null) {
                        ts = rs.getTimestamp("create_time");
                    }
                    info.setLockTime(toLocalDateTime(ts));
                    return info;
                }, deviceCode);
        } catch (Exception e) {
            log.warn("查询设备锁定状态失败, deviceCode={}, error={}", deviceCode, e.getMessage());
            return List.of();
        }
    }

    /**
     * 查询采集数据记录。
     * 对应 sc_device_collected_data 表，最近 50 条。
     */
    @Override
    public List<CollectedDataInfo> queryCollectedData(String taskNo) {
        if (taskNo == null || taskNo.isBlank()) {
            return List.of();
        }
        try {
            return jdbcTemplate.query(
                "SELECT id, device_code, task_no, task_item_no, command_no, node_code, " +
                "device_type, data_process_type, state, ack_state, content, " +
                "collected_index, required_count, create_time " +
                "FROM sc_device_collected_data WHERE task_no = ? " +
                "ORDER BY create_time DESC LIMIT 50",
                (rs, rowNum) -> {
                    CollectedDataInfo info = new CollectedDataInfo();
                    info.setId(rs.getObject("id", Long.class));
                    info.setDeviceCode(rs.getString("device_code"));
                    info.setTaskNo(rs.getString("task_no"));
                    info.setTaskItemNo(rs.getString("task_item_no"));
                    info.setCommandNo(rs.getString("command_no"));
                    info.setNodeCode(rs.getString("node_code"));
                    info.setDeviceType(rs.getString("device_type"));
                    info.setDataProcessType(rs.getString("data_process_type"));
                    info.setState(rs.getString("state"));
                    info.setAckState(rs.getString("ack_state"));
                    info.setContent(rs.getString("content"));
                    info.setCollectedIndex(rs.getObject("collected_index", Integer.class));
                    info.setRequiredCount(rs.getObject("required_count", Integer.class));
                    info.setCreateTime(toLocalDateTime(rs.getTimestamp("create_time")));
                    return info;
                }, taskNo);
        } catch (Exception e) {
            log.warn("查询采集数据失败, taskNo={}, error={}", taskNo, e.getMessage());
            return List.of();
        }
    }

    /**
     * 按 group_code 查询任务组内所有任务的状态。
     */
    @Override
    public List<TaskGroupMember> queryTaskGroupMembers(String groupCode) {
        if (groupCode == null || groupCode.isBlank()) {
            return List.of();
        }
        try {
            return jdbcTemplate.query(
                "SELECT task_no, task_state, biz_type, group_role, start_time, finish_time " +
                "FROM sc_task WHERE group_code = ? ORDER BY create_time",
                (rs, rowNum) -> {
                    TaskGroupMember m = new TaskGroupMember();
                    m.setTaskNo(rs.getString("task_no"));
                    m.setTaskState(rs.getString("task_state"));
                    m.setBizType(rs.getString("biz_type"));
                    m.setGroupRole(safeGetString(rs, "group_role"));
                    m.setStartTime(toLocalDateTime(rs.getTimestamp("start_time")));
                    m.setFinishTime(toLocalDateTime(rs.getTimestamp("finish_time")));
                    return m;
                }, groupCode);
        } catch (Exception e) {
            log.warn("查询任务组成员失败, groupCode={}, error={}", groupCode, e.getMessage());
            return List.of();
        }
    }

    /**
     * 查询货物实时状态。
     * 对应 sc_goods_state 表。
     */
    @Override
    public List<GoodsStateInfo> queryGoodsState(String taskNo) {
        if (taskNo == null || taskNo.isBlank()) {
            return List.of();
        }
        try {
            return jdbcTemplate.query(
                "SELECT goods_code, container_code, location_code, current_task_no, " +
                "current_task_item_no, current_node, state " +
                "FROM sc_goods_state WHERE current_task_no = ?",
                (rs, rowNum) -> {
                    GoodsStateInfo g = new GoodsStateInfo();
                    g.setGoodsCode(rs.getString("goods_code"));
                    g.setContainerCode(rs.getString("container_code"));
                    g.setLocationCode(rs.getString("location_code"));
                    g.setCurrentTaskNo(rs.getString("current_task_no"));
                    g.setCurrentTaskItemNo(rs.getString("current_task_item_no"));
                    g.setCurrentNode(rs.getString("current_node"));
                    g.setState(rs.getString("state"));
                    return g;
                }, taskNo);
        } catch (Exception e) {
            log.warn("查询货物状态失败, taskNo={}, error={}", taskNo, e.getMessage());
            return List.of();
        }
    }
}
