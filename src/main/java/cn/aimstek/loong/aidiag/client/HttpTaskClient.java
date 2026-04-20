package cn.aimstek.loong.aidiag.client;

import cn.aimstek.loong.aidiag.config.EnvConfig;
import cn.aimstek.loong.aidiag.dto.TaskDetail;
import cn.aimstek.loong.aidiag.exception.AiDiagnosisException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

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
