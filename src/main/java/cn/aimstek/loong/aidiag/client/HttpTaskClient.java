package cn.aimstek.loong.aidiag.client;

import cn.aimstek.loong.aidiag.config.EnvConfig;
import cn.aimstek.loong.aidiag.dto.DeviceInfo;
import cn.aimstek.loong.aidiag.dto.DeviceRunningState;
import cn.aimstek.loong.aidiag.dto.TaskDetail;
import cn.aimstek.loong.aidiag.exception.AiDiagnosisException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Primary
@Component
@RequiredArgsConstructor
public class HttpTaskClient implements TaskClient {

    private final EnvConfig envConfig;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FMT_MS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    @Override
    public TaskDetail getTaskDetail(String taskNo, String env) {
        String wcsUrl = envConfig.getWcsUrl();
        String url = wcsUrl + "/task/queryTaskByTaskNo?taskNo="
                + URLEncoder.encode(taskNo == null ? "" : taskNo, StandardCharsets.UTF_8);
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>("{}", headers);
            String json = restTemplate.postForObject(url, entity, String.class);
            JsonNode root = objectMapper.readTree(json);
            JsonNode data = root.path("data");
            if (data.isMissingNode() || data.isNull()) {
                throw new AiDiagnosisException("TASK_NOT_FOUND", "未找到任务: " + taskNo);
            }
            TaskDetail detail = mapTaskDetail(data);

            // 如果响应中子任务为空，主动查询子任务接口
            if (detail.getTaskItems() == null || detail.getTaskItems().isEmpty()) {
                List<TaskDetail.TaskItemDetail> items = queryTaskItemsFromApi(detail.getTaskNo(), wcsUrl);
                detail.setTaskItems(items);
            }

            // 加载指令列表
            if (detail.getCommands() == null || detail.getCommands().isEmpty()) {
                detail.setCommands(getCommands(detail.getTaskNo(), env));
            }
            return detail;
        } catch (AiDiagnosisException e) {
            throw e;
        } catch (HttpClientErrorException.NotFound e) {
            // 上游返回 404：通常是 wcsUrl 指向了 nginx 入口而非调度服务（缺少端口或路径前缀）
            log.error("查询任务详情失败(404): url={}, 上游响应非 JSON, 长度={} 字节",
                    url, e.getResponseBodyAsByteArray().length);
            throw new AiDiagnosisException(
                    "WCS_ENDPOINT_NOT_FOUND",
                    "调度服务接口未找到（HTTP 404）。请确认环境地址是否指向调度服务（默认端口 8088），"
                            + "当前地址：" + wcsUrl + "，请求路径：/task/queryTaskByTaskNo",
                    e);
        } catch (HttpStatusCodeException e) {
            log.error("查询任务详情失败({}): url={}", e.getStatusCode(), url, e);
            throw new AiDiagnosisException(
                    "WCS_HTTP_ERROR",
                    "调度服务返回错误：HTTP " + e.getStatusCode().value() + "，地址：" + wcsUrl,
                    e);
        } catch (ResourceAccessException e) {
            log.error("查询任务详情失败(网络不通): url={}", url, e);
            throw new AiDiagnosisException(
                    "WCS_UNREACHABLE",
                    "无法连接调度服务：" + wcsUrl + "（" + e.getMostSpecificCause().getMessage() + "）",
                    e);
        } catch (Exception e) {
            log.error("查询任务详情失败: {}", url, e);
            throw new AiDiagnosisException("TASK_QUERY_FAILED", "查询任务失败: " + e.getMessage(), e);
        }
    }

    @Override
    public List<TaskDetail> findRelatedTasks(TaskDetail focusTask, LocalDateTime windowStart, LocalDateTime windowEnd, String env) {
        if (focusTask == null) {
            return Collections.emptyList();
        }
        String wcsUrl = envConfig.getWcsUrl();
        String url = wcsUrl + "/task/queryRelatedTasks";

        String focusTaskNo = focusTask.getTaskNo();
        String timeFrom = windowStart != null ? windowStart.format(FMT) : null;
        String timeTo = windowEnd != null ? windowEnd.format(FMT) : null;

        Map<String, TaskDetail> merged = new LinkedHashMap<>();

        // 1. 按 rootTaskNo 查询同根任务
        if (focusTask.getRootTaskNo() != null && !focusTask.getRootTaskNo().isBlank()) {
            Map<String, Object> body = buildRelatedQueryBody("rootTaskNo", focusTask.getRootTaskNo(), timeFrom, timeTo);
            for (TaskDetail t : doRelatedQuery(url, body)) {
                if (t.getTaskNo() != null) merged.putIfAbsent(t.getTaskNo(), t);
            }
        }

        // 2. 按 groupCode 查询同组任务
        if (focusTask.getGroupCode() != null && !focusTask.getGroupCode().isBlank()) {
            Map<String, Object> body = buildRelatedQueryBody("groupCode", focusTask.getGroupCode(), timeFrom, timeTo);
            for (TaskDetail t : doRelatedQuery(url, body)) {
                if (t.getTaskNo() != null) merged.putIfAbsent(t.getTaskNo(), t);
            }
        }

        // 3. 按 parentTaskNo 查询父子任务（焦点任务作为父）
        if (focusTaskNo != null && !focusTaskNo.isBlank()) {
            Map<String, Object> body = buildRelatedQueryBody("parentTaskNo", focusTaskNo, timeFrom, timeTo);
            for (TaskDetail t : doRelatedQuery(url, body)) {
                if (t.getTaskNo() != null) merged.putIfAbsent(t.getTaskNo(), t);
            }
        }

        // 4. 按 preStartTaskNo / preEndTaskNo 查询依赖关系（焦点任务作为前置）
        if (focusTaskNo != null && !focusTaskNo.isBlank()) {
            Map<String, Object> body = buildRelatedQueryBody("preStartTaskNo", focusTaskNo, timeFrom, timeTo);
            for (TaskDetail t : doRelatedQuery(url, body)) {
                if (t.getTaskNo() != null) merged.putIfAbsent(t.getTaskNo(), t);
            }
            body = buildRelatedQueryBody("preEndTaskNo", focusTaskNo, timeFrom, timeTo);
            for (TaskDetail t : doRelatedQuery(url, body)) {
                if (t.getTaskNo() != null) merged.putIfAbsent(t.getTaskNo(), t);
            }
        }

        // 排除焦点任务自身
        if (focusTaskNo != null) {
            merged.remove(focusTaskNo);
        }

        List<TaskDetail> result = new ArrayList<>(merged.values());
        log.info("查询到关联任务 {} 条（排除自身 {}）", result.size(), focusTaskNo);
        return result;
    }

    @Override
    public List<TaskDetail.CommandDetail> getCommands(String taskNo, String env) {
        if (taskNo == null || taskNo.isBlank()) {
            return Collections.emptyList();
        }

        // 方式1：尝试 /task/queryCommandByTaskNo（可能不存在于所有 loong-platform 版本）
        List<TaskDetail.CommandDetail> commands = tryQueryCommandsByUrl(
                envConfig.getWcsUrl() + "/task/queryCommandByTaskNo?taskNo=" + taskNo);
        if (!commands.isEmpty()) {
            return commands;
        }

        // 方式2：尝试 /task/queryBatchCommand
        commands = tryQueryBatchCommands(taskNo);
        if (!commands.isEmpty()) {
            return commands;
        }

        log.info("任务{}未查询到指令数据（指令为补充信息，不影响诊断）", taskNo);
        return Collections.emptyList();
    }

    /**
     * 通过指定 URL 查询指令列表，响应格式: {"data": [...]}
     */
    private List<TaskDetail.CommandDetail> tryQueryCommandsByUrl(String url) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>("{}", headers);
            String json = restTemplate.postForObject(url, entity, String.class);
            JsonNode root = objectMapper.readTree(json);
            JsonNode data = root.path("data");
            List<TaskDetail.CommandDetail> commands = new ArrayList<>();
            if (data.isArray()) {
                for (JsonNode node : data) {
                    commands.add(mapCommand(node));
                }
            }
            return commands;
        } catch (Exception e) {
            log.warn("查询指令失败({}): {}", url, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 备用方式：通过 /task/queryBatchCommand 查询指令
     */
    private List<TaskDetail.CommandDetail> tryQueryBatchCommands(String taskNo) {
        String url = envConfig.getWcsUrl() + "/task/queryBatchCommand";
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            Map<String, Object> body = new HashMap<>();
            body.put("taskNo", taskNo);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            String json = restTemplate.postForObject(url, entity, String.class);
            JsonNode root = objectMapper.readTree(json);
            JsonNode data = root.path("data");
            JsonNode records = data.isArray() ? data : data.path("records");
            List<TaskDetail.CommandDetail> commands = new ArrayList<>();
            if (records.isArray()) {
                for (JsonNode node : records) {
                    commands.add(mapCommand(node));
                }
            }
            return commands;
        } catch (Exception e) {
            log.warn("备用指令查询失败({}): {}", url, e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<TaskDetail.TaskItemDetail> queryTaskItemsFromApi(String taskNo, String wcsUrl) {
        String url = wcsUrl + "/task/queryTaskItemByTaskNo?taskNo=" + taskNo;
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>("{}", headers);
            String json = restTemplate.postForObject(url, entity, String.class);
            JsonNode root = objectMapper.readTree(json);
            JsonNode dataNode = root.path("data");

            List<TaskDetail.TaskItemDetail> items = new ArrayList<>();
            if (dataNode.isArray()) {
                for (JsonNode itemNode : dataNode) {
                    items.add(mapTaskItem(itemNode));
                }
            }
            log.info("查询子任务成功, taskNo={}, count={}", taskNo, items.size());
            return items;
        } catch (Exception e) {
            log.warn("查询子任务失败, taskNo={}, error={}", taskNo, e.getMessage());
            return new ArrayList<>();
        }
    }

    private Map<String, Object> buildRelatedQueryBody(String field, String value, String createTimeFrom, String createTimeTo) {
        Map<String, Object> body = new HashMap<>();
        body.put(field, value);
        if (createTimeFrom != null) {
            body.put("createTimeFrom", createTimeFrom);
        }
        if (createTimeTo != null) {
            body.put("createTimeTo", createTimeTo);
        }
        body.put("limit", 20);
        return body;
    }

    private List<TaskDetail> doRelatedQuery(String url, Map<String, Object> body) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

            String json = restTemplate.postForObject(url, entity, String.class);
            JsonNode root = objectMapper.readTree(json);
            JsonNode data = root.path("data");
            // data 可能是数组，也可能是 records 包装
            JsonNode records = data.isArray() ? data : data.path("records");

            List<TaskDetail> result = new ArrayList<>();
            if (records.isArray()) {
                for (JsonNode node : records) {
                    result.add(mapTaskSummary(node));
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("关联任务查询失败, url={}, body={}: {}", url, body, e.getMessage());
            return Collections.emptyList();
        }
    }

    private TaskDetail mapTaskSummary(JsonNode node) {
        TaskDetail d = new TaskDetail();
        d.setTaskId(text(node, "taskId"));
        d.setTaskNo(text(node, "taskNo"));
        d.setRootTaskNo(text(node, "rootTaskNo"));
        d.setTaskSource(text(node, "taskSource"));
        d.setBizType(text(node, "bizType"));
        d.setTaskType(text(node, "taskType"));
        d.setTaskState(text(node, "taskState"));
        d.setPaused(text(node, "paused"));
        d.setStartNode(text(node, "startNode"));
        d.setEndNode(text(node, "endNode"));
        d.setGroupCode(text(node, "groupCode"));
        d.setPreStartTaskNo(text(node, "preStartTaskNo"));
        d.setPreEndTaskNo(text(node, "preEndTaskNo"));
        d.setParentTaskNo(text(node, "parentTaskNo"));
        d.setGoodsLocation(text(node, "goodsLocation"));
        d.setGoodsDeviceCode(text(node, "goodsDeviceCode"));
        d.setContainerList(text(node, "containerList"));
        d.setPriority(intVal(node, "bizPriority"));
        d.setCreateTime(dateTime(node, "createTime"));
        d.setStartTime(dateTime(node, "startTime"));
        d.setFinishTime(dateTime(node, "finishTime"));
        return d;
    }

    private TaskDetail mapTaskDetail(JsonNode data) {
        TaskDetail detail = mapTaskSummary(data);
        detail.setPlanFullPath(text(data, "planFullPath"));
        detail.setPlannedTime(dateTime(data, "plannedTime"));
        detail.setEstimatedStartTime(dateTime(data, "estimatedStartTime"));
        detail.setEstimatedFinishTime(dateTime(data, "estimatedFinishTime"));

        // 子任务列表
        List<TaskDetail.TaskItemDetail> items = new ArrayList<>();
        JsonNode itemsNode = data.path("taskItemList");
        if (!itemsNode.isArray()) {
            itemsNode = data.path("taskItemInfoDTOList");
        }
        if (itemsNode.isArray()) {
            for (JsonNode itemNode : itemsNode) {
                items.add(mapTaskItem(itemNode));
            }
        }
        detail.setTaskItems(items);
        return detail;
    }

    private TaskDetail.TaskItemDetail mapTaskItem(JsonNode itemNode) {
        TaskDetail.TaskItemDetail item = new TaskDetail.TaskItemDetail();
        item.setId(text(itemNode, "id"));
        item.setTaskNo(text(itemNode, "taskNo"));
        item.setTaskItemNo(text(itemNode, "taskItemNo"));
        item.setDeviceCode(text(itemNode, "deviceCode"));
        item.setTaskItemState(text(itemNode, "taskItemState"));
        item.setStartNode(text(itemNode, "startNode"));
        item.setEndNode(text(itemNode, "endNode"));
        item.setTaskAction(text(itemNode, "taskAction"));
        item.setPreTaskItemNo(text(itemNode, "preTaskItemNo"));
        item.setPlanIndex(intVal(itemNode, "planIndex"));
        item.setIssuedIndex(intVal(itemNode, "issuedIndex"));
        item.setExecutedIndex(intVal(itemNode, "executedIndex"));
        item.setPlanFullPath(text(itemNode, "planFullPath"));
        item.setStartTime(dateTime(itemNode, "startTime"));
        item.setFinishTime(dateTime(itemNode, "finishTime"));
        item.setCreateTime(dateTime(itemNode, "createTime"));
        return item;
    }

    private TaskDetail.CommandDetail mapCommand(JsonNode node) {
        TaskDetail.CommandDetail c = new TaskDetail.CommandDetail();
        c.setId(text(node, "id"));
        c.setTaskItemNo(text(node, "taskItemNo"));
        c.setCommandNo(text(node, "commandNo"));
        c.setPlcTaskNo(text(node, "plcTaskNo"));
        c.setDeviceCode(text(node, "deviceCode"));
        c.setDeviceType(text(node, "deviceType"));
        c.setCommandState(text(node, "commandState"));
        c.setCommandType(text(node, "commandType"));
        c.setStartNode(text(node, "startNode"));
        c.setEndNode(text(node, "endNode"));
        c.setCommandDetail(text(node, "commandDetail"));
        c.setCommandResult(text(node, "commandResult"));
        c.setReportState(text(node, "reportState"));
        c.setErrorCode(text(node, "errorCode"));
        c.setErrorMsg(text(node, "errorMsg"));
        c.setExecResult(text(node, "execResult"));
        c.setAckResult(text(node, "ackResult"));
        c.setAckDetail(text(node, "ackDetail"));
        c.setStartTime(dateTime(node, "startTime"));
        c.setFinishTime(dateTime(node, "finishTime"));
        c.setCreateTime(dateTime(node, "createTime"));
        return c;
    }

    private String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v != null && !v.isNull()) ? v.asText() : null;
    }

    private Integer intVal(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v != null && !v.isNull() && v.isNumber()) ? v.asInt() : null;
    }

    private Long longVal(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        if (v.isNumber()) return v.asLong();
        try {
            return Long.parseLong(v.asText());
        } catch (Exception ex) {
            return null;
        }
    }

    private LocalDateTime dateTime(JsonNode node, String field) {
        String s = text(node, field);
        if (s == null || s.isEmpty()) return null;
        try {
            return LocalDateTime.parse(s, FMT_MS);
        } catch (Exception e) {
            try {
                return LocalDateTime.parse(s, FMT);
            } catch (Exception e2) {
                try {
                    return LocalDateTime.parse(s);
                } catch (Exception ex) {
                    return null;
                }
            }
        }
    }

    /**
     * 查询设备实时状态信息
     */
    public DeviceInfo getDeviceInfo(String deviceCode, String env) {
        String wcsUrl = envConfig.getWcsUrl();
        String url = wcsUrl + "/iot/deviceCache/client/get/" + deviceCode;
        try {
            String json = restTemplate.getForObject(url, String.class);
            JsonNode root = objectMapper.readTree(json);
            JsonNode data = root.path("data");
            if (data.isMissingNode() || data.isNull()) {
                return null;
            }
            return mapDeviceInfo(data);
        } catch (Exception e) {
            log.warn("查询设备信息失败, deviceCode={}, error={}", deviceCode, e.getMessage());
            return null;
        }
    }

    private DeviceInfo mapDeviceInfo(JsonNode data) {
        DeviceInfo info = new DeviceInfo();
        info.setDeviceCode(data.path("deviceCode").asText(null));
        info.setDeviceTypeCode(data.path("deviceTypeCode").asText(null));
        info.setDeviceTypeName(data.path("deviceTypeName").asText(null));
        info.setCollectTime(data.path("collectTime").asText(null));
        info.setOnline(data.path("online").asBoolean(false));

        // lastUpdateMap
        JsonNode lastUpdateNode = data.path("lastUpdateMap");
        if (lastUpdateNode.isObject()) {
            Map<String, String> lastUpdateMap = new LinkedHashMap<>();
            lastUpdateNode.fields().forEachRemaining(entry ->
                lastUpdateMap.put(entry.getKey(), entry.getValue().asText()));
            info.setLastUpdateMap(lastUpdateMap);
        }

        // stateInfo
        JsonNode stateNode = data.path("stateInfo");
        if (!stateNode.isMissingNode() && !stateNode.isNull()) {
            DeviceInfo.StateInfo stateInfo = new DeviceInfo.StateInfo();
            stateInfo.setPointCode(stateNode.path("pointCode").asInt(0));
            stateInfo.setWorkMode(stateNode.path("workMode").asInt(0));
            stateInfo.setWorkState(stateNode.path("workState").asInt(0));
            stateInfo.setTaskNo(stateNode.path("taskNo").asInt(0));
            stateInfo.setTaskState(stateNode.path("taskState").asInt(0));
            stateInfo.setLoadState(stateNode.path("loadState").asInt(0));
            stateInfo.setTaskError(stateNode.path("taskError").asInt(0));
            // alarmInfoList
            JsonNode alarmNode = stateNode.path("alarmInfoList");
            if (alarmNode.isArray()) {
                List<Object> alarms = new ArrayList<>();
                for (JsonNode alarm : alarmNode) {
                    alarms.add(alarm.toString());
                }
                stateInfo.setAlarmInfoList(alarms);
            }
            info.setStateInfo(stateInfo);
        }
        return info;
    }

    /**
     * 查询设备 DCS 运行状态（含报警信息）。
     * 接口：POST /dcs/queryDeviceByDeviceCode?deviceCode=xxx
     */
    public DeviceRunningState getDeviceRunningState(String deviceCode) {
        if (deviceCode == null || deviceCode.isBlank()) {
            return null;
        }
        String url = envConfig.getWcsUrl() + "/dcs/queryDeviceByDeviceCode?deviceCode=" + deviceCode;
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>("{}", headers);
            String json = restTemplate.postForObject(url, entity, String.class);
            JsonNode root = objectMapper.readTree(json);
            JsonNode data = root.path("data");
            if (data.isMissingNode() || data.isNull()) {
                return null;
            }
            return mapDeviceRunningState(data);
        } catch (Exception e) {
            log.warn("查询设备 DCS 运行状态失败, deviceCode={}, error={}", deviceCode, e.getMessage());
            return null;
        }
    }

    private DeviceRunningState mapDeviceRunningState(JsonNode data) {
        DeviceRunningState s = new DeviceRunningState();
        s.setDeviceCode(text(data, "deviceCode"));
        s.setDeviceType(text(data, "deviceType"));
        s.setParentDeviceCode(text(data, "parentDeviceCode"));
        JsonNode onlineNode = data.get("onlineState");
        if (onlineNode != null && !onlineNode.isNull()) {
            s.setOnlineState(onlineNode.asBoolean());
        }
        s.setReportTime(text(data, "reportTime"));
        s.setWorkMode(text(data, "workMode"));
        s.setDeviceState(text(data, "deviceState"));
        s.setDcsTaskState(text(data, "dcsTaskState"));
        s.setHasTask(text(data, "hasTask"));
        s.setHasGoods(text(data, "hasGoods"));
        s.setPlcTaskNo(text(data, "plcTaskNo"));

        List<DeviceRunningState.AlarmInfo> alarms = new ArrayList<>();
        JsonNode alarmNode = data.path("alarmInfoList");
        if (alarmNode.isArray()) {
            for (JsonNode n : alarmNode) {
                DeviceRunningState.AlarmInfo a = new DeviceRunningState.AlarmInfo();
                a.setAlarmCode(text(n, "alarmCode"));
                a.setAlarmMessage(text(n, "alarmMessage"));
                a.setAlarmLevel(text(n, "alarmLevel"));
                a.setAlarmType(text(n, "alarmType"));
                a.setFirstAlarmTime(text(n, "firstAlarmTime"));
                a.setTimestamp(text(n, "timestamp"));
                alarms.add(a);
            }
        }
        s.setAlarmInfoList(alarms);
        return s;
    }
}
