package cn.aimstek.loong.aidiag.client;

import cn.aimstek.loong.aidiag.config.EnvConfig;
import cn.aimstek.loong.aidiag.dto.TaskDetail;
import cn.aimstek.loong.aidiag.exception.AiDiagnosisException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Primary
@Component
@RequiredArgsConstructor
public class HttpTaskClient implements TaskClient {

    private final EnvConfig envConfig;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    @Override
    public TaskDetail getTaskDetail(String taskId, String env) {
        String wcsUrl = envConfig.getWcsUrl();
        String url = wcsUrl + "/task/web/" + taskId;
        try {
            String json = restTemplate.getForObject(url, String.class);
            JsonNode root = objectMapper.readTree(json);
            JsonNode data = root.path("data");
            if (data.isMissingNode() || data.isNull()) {
                throw new AiDiagnosisException("TASK_NOT_FOUND", "未找到任务: " + taskId);
            }
            return mapTaskDetail(data);
        } catch (AiDiagnosisException e) {
            throw e;
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
        String url = wcsUrl + "/task/web/taskManagerPageQuery";

        String focusId = focusTask.getWmsTaskNo() != null ? focusTask.getWmsTaskNo() : focusTask.getTaskId();
        String timeFrom = windowStart != null ? windowStart.format(FMT) : null;
        String timeTo = windowEnd != null ? windowEnd.format(FMT) : null;

        // 用 LinkedHashSet 按 wmsTaskNo 去重，保持插入顺序
        Map<String, TaskDetail> merged = new LinkedHashMap<>();

        // 1. 按容器号查询
        if (focusTask.getContainerCode() != null && !focusTask.getContainerCode().isBlank()) {
            Map<String, Object> body = buildPageQueryBody("containerCode", focusTask.getContainerCode(), timeFrom, timeTo);
            List<TaskDetail> byContainer = doPageQuery(url, body);
            for (TaskDetail t : byContainer) {
                merged.putIfAbsent(t.getWmsTaskNo(), t);
            }
        }

        // 2. 按业务起点查询（WCS TasTaskManagerPageDto 字段为 startPoint）
        String fromPoint = focusTask.getDefiniteFrom() != null ? focusTask.getDefiniteFrom() : focusTask.getBusinessFrom();
        if (fromPoint != null && !fromPoint.isBlank()) {
            Map<String, Object> body = buildPageQueryBody("startPoint", fromPoint, timeFrom, timeTo);
            List<TaskDetail> byFrom = doPageQuery(url, body);
            for (TaskDetail t : byFrom) {
                merged.putIfAbsent(t.getWmsTaskNo(), t);
            }
        }

        // 3. 按业务终点查询（WCS TasTaskManagerPageDto 字段为 endPoint）
        String toPoint = focusTask.getDefiniteTo() != null ? focusTask.getDefiniteTo() : focusTask.getBusinessTo();
        if (toPoint != null && !toPoint.isBlank()) {
            Map<String, Object> body = buildPageQueryBody("endPoint", toPoint, timeFrom, timeTo);
            List<TaskDetail> byTo = doPageQuery(url, body);
            for (TaskDetail t : byTo) {
                merged.putIfAbsent(t.getWmsTaskNo(), t);
            }
        }

        // 排除焦点任务自身
        merged.remove(focusId);

        List<TaskDetail> result = new ArrayList<>(merged.values());
        log.info("查询到关联任务 {} 条（排除自身 {}）", result.size(), focusId);
        return result;
    }

    private Map<String, Object> buildPageQueryBody(String field, String value, String createTimeFrom, String createTimeTo) {
        Map<String, Object> body = new HashMap<>();
        body.put(field, value);
        body.put("pageNum", 1);
        body.put("pageSize", 50);
        if (createTimeFrom != null) {
            body.put("createTimeFrom", createTimeFrom);
        }
        if (createTimeTo != null) {
            body.put("createTimeTo", createTimeTo);
        }
        return body;
    }

    private List<TaskDetail> doPageQuery(String url, Map<String, Object> body) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

            String json = restTemplate.postForObject(url, entity, String.class);
            JsonNode root = objectMapper.readTree(json);
            JsonNode records = root.path("data").path("records");

            List<TaskDetail> result = new ArrayList<>();
            if (records.isArray()) {
                for (JsonNode node : records) {
                    TaskDetail detail = mapTaskSummary(node);
                    result.add(detail);
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("关联任务分页查询失败, url={}, body={}: {}", url, body, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 将 taskManagerPageQuery 返回的单条记录映射为 TaskDetail（仅主任务核心字段，不含子任务/执行单）
     */
    private TaskDetail mapTaskSummary(JsonNode node) {
        TaskDetail d = new TaskDetail();
        d.setTaskId(text(node, "wmsTaskNo"));
        d.setWmsTaskNo(text(node, "wmsTaskNo"));
        d.setTaskSource(text(node, "taskSource"));
        d.setBusinessType(text(node, "businessType"));
        d.setTaskType(intVal(node, "taskType"));
        d.setTaskState(text(node, "taskState"));
        d.setHandleState(text(node, "handleState"));
        d.setContainerCode(text(node, "containerCode"));
        d.setBusinessFrom(text(node, "businessFrom"));
        d.setDefiniteFrom(text(node, "definiteFrom"));
        d.setBusinessTo(text(node, "businessTo"));
        d.setDefiniteTo(text(node, "definiteTo"));
        d.setErrorMessage(text(node, "errorMessage"));
        d.setPriority(intVal(node, "priority"));
        d.setCreateTime(dateTime(node, "createTime"));
        d.setDefiniteTime(dateTime(node, "definiteTime"));
        d.setSplitTime(dateTime(node, "splitTime"));
        d.setStartTime(dateTime(node, "startTime"));
        d.setFinishTime(dateTime(node, "finishTime"));
        return d;
    }

    private TaskDetail mapTaskDetail(JsonNode data) {
        TaskDetail detail = new TaskDetail();
        detail.setTaskId(text(data, "wmsTaskNo"));
        detail.setWmsTaskNo(text(data, "wmsTaskNo"));
        detail.setTaskSource(text(data, "taskSource"));
        detail.setBusinessType(text(data, "businessType"));
        detail.setTaskType(intVal(data, "taskType"));
        detail.setTaskState(text(data, "taskState"));
        detail.setHandleState(text(data, "handleState"));
        detail.setContainerCode(text(data, "containerCode"));
        detail.setBusinessFrom(text(data, "businessFrom"));
        detail.setDefiniteFrom(text(data, "definiteFrom"));
        detail.setBusinessTo(text(data, "businessTo"));
        detail.setDefiniteTo(text(data, "definiteTo"));
        detail.setErrorMessage(text(data, "abnormalMessage"));
        detail.setPriority(intVal(data, "priority"));
        detail.setCreateTime(dateTime(data, "createTime"));
        detail.setDefiniteTime(dateTime(data, "definiteTime"));
        detail.setSplitTime(dateTime(data, "splitTime"));
        detail.setStartTime(dateTime(data, "startTime"));
        detail.setFinishTime(dateTime(data, "finishTime"));

        // 子任务
        List<TaskDetail.TaskItemDetail> items = new ArrayList<>();
        List<TaskDetail.TicketDetail> allTickets = new ArrayList<>();
        JsonNode itemsNode = data.path("taskItemInfoDTOList");
        if (itemsNode.isArray()) {
            for (JsonNode itemNode : itemsNode) {
                TaskDetail.TaskItemDetail item = new TaskDetail.TaskItemDetail();
                item.setId(text(itemNode, "id"));
                item.setWmsTaskNo(text(itemNode, "wmsTaskNo"));
                item.setStartPoint(text(itemNode, "startPoint"));
                item.setEndPoint(text(itemNode, "endPoint"));
                item.setDeviceCode(text(itemNode, "deviceCode"));
                item.setDeviceType(text(itemNode, "deviceType"));
                item.setTaskState(text(itemNode, "taskState"));
                item.setCheckStatus(text(itemNode, "checkStatus"));
                item.setStartTime(dateTime(itemNode, "startTime"));
                item.setFinishTime(dateTime(itemNode, "finishTime"));
                item.setCreateTime(dateTime(itemNode, "createTime"));
                items.add(item);

                // 查询该子任务的执行单
                allTickets.addAll(queryTickets(item.getId()));
            }
        }
        detail.setTaskItems(items);
        detail.setTickets(allTickets);
        return detail;
    }

    private List<TaskDetail.TicketDetail> queryTickets(String taskItemId) {
        List<TaskDetail.TicketDetail> tickets = new ArrayList<>();
        if (taskItemId == null) return tickets;
        try {
            String url = envConfig.getWcsUrl() + "/dcs/web/" + taskItemId;
            String json = restTemplate.getForObject(url, String.class);
            JsonNode root = objectMapper.readTree(json);
            JsonNode dataNode = root.path("data");
            if (dataNode.isArray()) {
                for (JsonNode tkNode : dataNode) {
                    TaskDetail.TicketDetail tk = new TaskDetail.TicketDetail();
                    tk.setId(text(tkNode, "id"));
                    tk.setTaskItemId(text(tkNode, "taskItemId"));
                    tk.setFunction(text(tkNode, "function"));
                    tk.setStartPoint(text(tkNode, "startPoint"));
                    tk.setEndPoint(text(tkNode, "endPoint"));
                    tk.setDeviceCode(text(tkNode, "deviceCode"));
                    tk.setDeviceName(text(tkNode, "deviceName"));
                    tk.setDeviceType(text(tkNode, "deviceType"));
                    tk.setTaskState(text(tkNode, "taskState"));
                    tk.setPlcTaskId(text(tkNode, "plcTaskId"));
                    tk.setTaskSort(intVal(tkNode, "taskSort"));
                    tk.setStartTime(dateTime(tkNode, "startTime"));
                    tk.setFinishTime(dateTime(tkNode, "finishTime"));
                    tk.setCreateTime(dateTime(tkNode, "createTime"));
                    tickets.add(tk);
                }
            }
        } catch (Exception e) {
            log.warn("查询子任务{}的执行单失败: {}", taskItemId, e.getMessage());
        }
        return tickets;
    }

    private String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v != null && !v.isNull()) ? v.asText() : null;
    }

    private Integer intVal(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v != null && !v.isNull() && v.isNumber()) ? v.asInt() : null;
    }

    private LocalDateTime dateTime(JsonNode node, String field) {
        String s = text(node, field);
        if (s == null || s.isEmpty()) return null;
        try {
            return LocalDateTime.parse(s, FMT);
        } catch (Exception e) {
            try {
                return LocalDateTime.parse(s);
            } catch (Exception ex) {
                return null;
            }
        }
    }
}
