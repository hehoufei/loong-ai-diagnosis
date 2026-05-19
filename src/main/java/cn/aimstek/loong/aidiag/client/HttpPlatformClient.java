package cn.aimstek.loong.aidiag.client;

import cn.aimstek.loong.aidiag.client.dto.*;
import cn.aimstek.loong.aidiag.config.PlatformProperties;
import cn.aimstek.loong.aidiag.exception.AiDiagnosisException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * HTTP 方式访问 loong-platform 接口。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HttpPlatformClient implements PlatformClient {

    private final PlatformProperties properties;
    private final EnumReverseMapper enumMapper;
    private final ObjectMapper objectMapper;
    private RestTemplate restTemplate;

    @PostConstruct
    public void init() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMs()));
        this.restTemplate = new RestTemplate(factory);
        log.info("HttpPlatformClient 初始化完成，baseUrl={}, connectTimeout={}ms, readTimeout={}ms",
                properties.getBaseUrl(), properties.getConnectTimeoutMs(), properties.getReadTimeoutMs());
    }

    @Override
    public PlatformTask queryTask(String taskNo) {
        String url = properties.getBaseUrl() + "/task/queryTaskByTaskNo?taskNo=" + taskNo;
        log.info("PLATFORM_CALL api=queryTask taskNo={} url={}", taskNo, url);
        long start = System.currentTimeMillis();
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, null, String.class);
            long elapsed = System.currentTimeMillis() - start;
            log.info("PLATFORM_CALL api=queryTask taskNo={} elapsedMs={} status={}", taskNo, elapsed, response.getStatusCode());

            if (response.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new AiDiagnosisException("TASK_NOT_FOUND", "任务不存在: " + taskNo);
            }

            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode dataNode = root.path("data");
            if (dataNode.isMissingNode() || dataNode.isNull()) {
                throw new AiDiagnosisException("TASK_NOT_FOUND", "任务不存在: " + taskNo);
            }

            PlatformTask task = new PlatformTask();
            task.setTaskNo(dataNode.path("taskNo").asText(null));
            task.setRootTaskNo(dataNode.path("rootTaskNo").asText(null));
            task.setParentTaskNo(dataNode.path("parentTaskNo").asText(null));
            task.setGroupCode(dataNode.path("groupCode").asText(null));
            task.setGroupType(dataNode.path("groupType").asText(null));
            task.setGroupRole(dataNode.path("groupRole").asText(null));
            task.setTaskSource(dataNode.path("taskSource").asText(null));
            task.setBizType(dataNode.path("bizType").asText(null));
            task.setTaskType(dataNode.path("taskType").asText(null));
            task.setTaskState(dataNode.path("taskState").asText(null));
            task.setPaused(dataNode.path("paused").asText(null));
            task.setStartNode(dataNode.path("startNode").asText(null));
            task.setEndNode(dataNode.path("endNode").asText(null));
            task.setBizPriority(dataNode.path("bizPriority").isInt() ? dataNode.path("bizPriority").asInt() : null);
            task.setCreateTime(parseDateTime(dataNode.path("createTime").asText(null)));
            task.setPlannedTime(parseDateTime(dataNode.path("plannedTime").asText(null)));
            task.setStartTime(parseDateTime(dataNode.path("startTime").asText(null)));
            task.setFinishTime(parseDateTime(dataNode.path("finishTime").asText(null)));
            task.setErrorMessage(dataNode.path("errorMessage").asText(null));

            return task;
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new AiDiagnosisException("TASK_NOT_FOUND", "任务不存在: " + taskNo);
            }
            throw new AiDiagnosisException("PLATFORM_ERROR", "平台接口调用失败: " + e.getMessage(), e);
        } catch (HttpServerErrorException e) {
            throw new AiDiagnosisException("PLATFORM_UNAVAILABLE", "平台服务不可达(5xx): " + e.getMessage(), e);
        } catch (AiDiagnosisException e) {
            throw e;
        } catch (Exception e) {
            throw new AiDiagnosisException("PLATFORM_ERROR", "平台接口调用异常: " + e.getMessage(), e);
        }
    }

    @Override
    public TaskItemBundle queryTaskItemBundle(String taskNo) {
        String url = properties.getBaseUrl() + "/api/admin/scheduler/item/getTaskItemDetails?taskNo=" + taskNo;
        log.info("PLATFORM_CALL api=queryTaskItemBundle taskNo={} url={}", taskNo, url);
        long start = System.currentTimeMillis();
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            long elapsed = System.currentTimeMillis() - start;
            log.info("PLATFORM_CALL api=queryTaskItemBundle taskNo={} elapsedMs={} status={}", taskNo, elapsed, response.getStatusCode());

            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode dataNode = root.path("data");

            TaskItemBundle bundle = new TaskItemBundle();
            bundle.setItems(new ArrayList<>());
            bundle.setRelations(new ArrayList<>());

            if (dataNode.isMissingNode() || dataNode.isNull()) {
                return bundle;
            }

            JsonNode itemsNode = dataNode.path("items");
            if (itemsNode.isArray()) {
                for (JsonNode itemNode : itemsNode) {
                    PlatformTaskItem item = new PlatformTaskItem();
                    item.setTaskNo(itemNode.path("taskNo").asText(null));
                    item.setTaskItemNo(itemNode.path("taskItemNo").asText(null));
                    item.setPreTaskItemNo(itemNode.path("preTaskItemNo").asText(null));
                    item.setDeviceCode(itemNode.path("deviceCode").asText(null));
                    item.setDeviceType(itemNode.path("deviceType").asText(null));
                    item.setTaskItemState(enumMapper.toTaskItemStateCode(itemNode.path("taskItemState").asText(null)));
                    item.setTaskAction(itemNode.path("taskAction").asText(null));
                    item.setScheduleChannel(enumMapper.toScheduleChannelCode(itemNode.path("scheduleChannel").asText(null)));
                    item.setPaused(enumMapper.toPausedCode(itemNode.path("paused").asText(null)));
                    item.setStartNode(itemNode.path("startNode").asText(null));
                    item.setEndNode(itemNode.path("endNode").asText(null));
                    item.setStartTime(parseDateTime(itemNode.path("startTime").asText(null)));
                    item.setFinishTime(parseDateTime(itemNode.path("finishTime").asText(null)));

                    JsonNode commandsNode = itemNode.path("commands");
                    if (commandsNode.isArray()) {
                        List<PlatformCommand> commands = new ArrayList<>();
                        for (JsonNode cmdNode : commandsNode) {
                            PlatformCommand cmd = new PlatformCommand();
                            cmd.setTaskItemNo(cmdNode.path("taskItemNo").asText(null));
                            cmd.setCommandNo(cmdNode.path("commandNo").asText(null));
                            cmd.setPlcTaskNo(cmdNode.path("plcTaskNo").asText(null));
                            cmd.setDeviceCode(cmdNode.path("deviceCode").asText(null));
                            cmd.setDeviceType(cmdNode.path("deviceType").asText(null));
                            cmd.setCommandType(cmdNode.path("commandType").asText(null));
                            cmd.setCommandState(enumMapper.toCommandStateCode(cmdNode.path("commandState").asText(null)));
                            cmd.setCommandDetail(cmdNode.path("commandDetail").asText(null));
                            cmd.setCommandResult(cmdNode.path("commandResult").asText(null));
                            cmd.setStartTime(parseDateTime(cmdNode.path("startTime").asText(null)));
                            cmd.setFinishTime(parseDateTime(cmdNode.path("finishTime").asText(null)));
                            commands.add(cmd);
                        }
                        item.setCommands(commands);
                    }

                    bundle.getItems().add(item);
                }
            }

            JsonNode relationsNode = dataNode.path("relations");
            if (relationsNode.isArray()) {
                for (JsonNode relNode : relationsNode) {
                    PlatformTaskRelation rel = new PlatformTaskRelation();
                    rel.setFromTaskItemNo(relNode.path("fromTaskItemNo").asText(null));
                    rel.setToTaskItemNo(relNode.path("toTaskItemNo").asText(null));
                    bundle.getRelations().add(rel);
                }
            }

            return bundle;
        } catch (HttpServerErrorException e) {
            throw new AiDiagnosisException("PLATFORM_UNAVAILABLE", "平台服务不可达(5xx): " + e.getMessage(), e);
        } catch (Exception e) {
            log.warn("queryTaskItemBundle 失败: taskNo={}, error={}", taskNo, e.getMessage());
            TaskItemBundle emptyBundle = new TaskItemBundle();
            emptyBundle.setItems(new ArrayList<>());
            emptyBundle.setRelations(new ArrayList<>());
            return emptyBundle;
        }
    }

    @Override
    public PlatformTaskGroup queryTaskGroup(String groupCode) {
        if (groupCode == null || groupCode.isEmpty()) {
            return null;
        }
        String url = properties.getBaseUrl() + "/api/admin/scheduler/task/getTasksByGroupCode?groupCode=" + groupCode;
        log.info("PLATFORM_CALL api=queryTaskGroup groupCode={} url={}", groupCode, url);
        long start = System.currentTimeMillis();
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            long elapsed = System.currentTimeMillis() - start;
            log.info("PLATFORM_CALL api=queryTaskGroup groupCode={} elapsedMs={} status={}", groupCode, elapsed, response.getStatusCode());

            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode dataNode = root.path("data");

            if (dataNode.isMissingNode() || dataNode.isNull()) {
                return null;
            }

            PlatformTaskGroup group = new PlatformTaskGroup();
            group.setGroupCode(dataNode.path("groupCode").asText(null));
            group.setGroupType(dataNode.path("groupType").asText(null));
            group.setMainSize(dataNode.path("mainSize").isInt() ? dataNode.path("mainSize").asInt() : null);
            group.setSubSize(dataNode.path("subSize").isInt() ? dataNode.path("subSize").asInt() : null);
            group.setSplitFinish(enumMapper.toSplitFinishCode(dataNode.path("splitFinish").asText(null)));
            group.setSplitFinishTime(parseDateTime(dataNode.path("splitFinishTime").asText(null)));

            JsonNode tasksNode = dataNode.path("tasks");
            if (tasksNode.isArray()) {
                List<PlatformTask> tasks = new ArrayList<>();
                for (JsonNode taskNode : tasksNode) {
                    PlatformTask task = new PlatformTask();
                    task.setTaskNo(taskNode.path("taskNo").asText(null));
                    task.setTaskState(enumMapper.toTaskStateCode(taskNode.path("taskState").asText(null)));
                    task.setGroupRole(taskNode.path("groupRole").asText(null));
                    tasks.add(task);
                }
                group.setTasksInGroup(tasks);
            }

            return group;
        } catch (Exception e) {
            log.warn("queryTaskGroup 失败（不阻断）: groupCode={}, error={}", groupCode, e.getMessage());
            return null;
        }
    }

    @Override
    public PlatformDeviceStatus queryDevice(String deviceCode) {
        if (deviceCode == null || deviceCode.isEmpty()) {
            return null;
        }
        String url = properties.getBaseUrl() + "/dcs/queryDeviceByDeviceCode?deviceCode=" + deviceCode;
        log.info("PLATFORM_CALL api=queryDevice deviceCode={} url={}", deviceCode, url);
        long start = System.currentTimeMillis();
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, null, String.class);
            long elapsed = System.currentTimeMillis() - start;
            log.info("PLATFORM_CALL api=queryDevice deviceCode={} elapsedMs={} status={}", deviceCode, elapsed, response.getStatusCode());

            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode dataNode = root.path("data");

            if (dataNode.isMissingNode() || dataNode.isNull()) {
                return null;
            }

            PlatformDeviceStatus device = new PlatformDeviceStatus();
            device.setDeviceCode(dataNode.path("deviceCode").asText(null));
            device.setDeviceType(dataNode.path("deviceType").asText(null));
            device.setDeviceStatus(dataNode.path("deviceStatus").asText(null));
            device.setOnlineStatus(dataNode.path("onlineStatus").asText(null));

            return device;
        } catch (Exception e) {
            log.warn("queryDevice 失败（不阻断）: deviceCode={}, error={}", deviceCode, e.getMessage());
            return null;
        }
    }

    private LocalDateTime parseDateTime(String dateTimeStr) {
        if (dateTimeStr == null || dateTimeStr.isEmpty() || "null".equals(dateTimeStr)) {
            return null;
        }
        try {
            return LocalDateTime.parse(dateTimeStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (Exception e) {
            log.warn("日期解析失败: {}", dateTimeStr);
            return null;
        }
    }
}
