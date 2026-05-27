package cn.aimstek.loong.aidiag.controller;

import cn.aimstek.loong.aidiag.common.BaseResponse;
import cn.aimstek.loong.aidiag.common.Response;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 快捷任务下发历史 API
 * 按天持久化到 ~/.loong-ai-diagnosis/quick-task-history/yyyy-MM-dd.json
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/quick-task-history")
public class QuickTaskHistoryController {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private final ObjectMapper mapper;

    public QuickTaskHistoryController(ObjectMapper mapper) {
        ObjectMapper m = mapper.copy();
        m.enable(SerializationFeature.INDENT_OUTPUT);
        this.mapper = m;
    }

    @PostConstruct
    public void init() {
        File dir = historyDir();
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
    }

    // ============== 记录一条下发历史 ==============

    @PostMapping("/record")
    public Response<Void> record(@RequestBody HistoryRecord record) {
        try {
            if (record.getTimestamp() == null || record.getTimestamp().isBlank()) {
                record.setTimestamp(java.time.LocalDateTime.now()
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")));
            }
            String date = record.getTimestamp().substring(0, 10); // yyyy-MM-dd
            File file = dayFile(date);
            List<HistoryRecord> records = loadDayRecords(file);
            records.add(record);
            mapper.writeValue(file, records);
            return BaseResponse.success(null);
        } catch (Exception e) {
            log.error("保存快捷任务历史失败", e);
            return BaseResponse.failure("SAVE_ERROR", e.getMessage());
        }
    }

    // ============== 批量记录 ==============

    @PostMapping("/record-batch")
    public Response<Void> recordBatch(@RequestBody List<HistoryRecord> records) {
        try {
            // 按日期分组
            Map<String, List<HistoryRecord>> byDate = new LinkedHashMap<>();
            for (HistoryRecord r : records) {
                if (r.getTimestamp() == null || r.getTimestamp().isBlank()) {
                    r.setTimestamp(java.time.LocalDateTime.now()
                            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")));
                }
                String date = r.getTimestamp().substring(0, 10);
                byDate.computeIfAbsent(date, k -> new ArrayList<>()).add(r);
            }
            for (Map.Entry<String, List<HistoryRecord>> entry : byDate.entrySet()) {
                File file = dayFile(entry.getKey());
                List<HistoryRecord> existing = loadDayRecords(file);
                existing.addAll(entry.getValue());
                mapper.writeValue(file, existing);
            }
            return BaseResponse.success(null);
        } catch (Exception e) {
            log.error("批量保存快捷任务历史失败", e);
            return BaseResponse.failure("SAVE_ERROR", e.getMessage());
        }
    }

    // ============== 查询某天的历史 ==============

    @GetMapping("/day/{date}")
    public Response<List<HistoryRecord>> getDay(@PathVariable String date) {
        try {
            File file = dayFile(date);
            List<HistoryRecord> records = loadDayRecords(file);
            return BaseResponse.success(records);
        } catch (Exception e) {
            return BaseResponse.failure("LOAD_ERROR", e.getMessage());
        }
    }

    // ============== 查询今天的历史 ==============

    @GetMapping("/today")
    public Response<List<HistoryRecord>> getToday() {
        return getDay(LocalDate.now().format(DATE_FMT));
    }

    // ============== 列出所有有记录的日期 ==============

    @GetMapping("/dates")
    public Response<List<String>> listDates() {
        File dir = historyDir();
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files == null || files.length == 0) {
            return BaseResponse.success(Collections.emptyList());
        }
        List<String> dates = Arrays.stream(files)
                .map(f -> f.getName().replace(".json", ""))
                .sorted(Comparator.reverseOrder())
                .collect(Collectors.toList());
        return BaseResponse.success(dates);
    }

    // ============== 统计摘要 ==============

    @GetMapping("/summary/{date}")
    public Response<DaySummary> summary(@PathVariable String date) {
        try {
            File file = dayFile(date);
            List<HistoryRecord> records = loadDayRecords(file);
            DaySummary s = new DaySummary();
            s.setDate(date);
            s.setTotal(records.size());
            s.setSuccess((int) records.stream().filter(r -> Boolean.TRUE.equals(r.getSuccess())).count());
            s.setFailed((int) records.stream().filter(r -> Boolean.FALSE.equals(r.getSuccess())).count());
            // 按类型统计
            Map<String, Integer> byType = new LinkedHashMap<>();
            for (HistoryRecord r : records) {
                String t = r.getTaskType() != null ? r.getTaskType() : "UNKNOWN";
                byType.merge(t, 1, Integer::sum);
            }
            s.setByType(byType);
            // 按服务器统计
            Map<String, Integer> byServer = new LinkedHashMap<>();
            for (HistoryRecord r : records) {
                String sv = r.getServer() != null ? r.getServer() : "unknown";
                byServer.merge(sv, 1, Integer::sum);
            }
            s.setByServer(byServer);
            return BaseResponse.success(s);
        } catch (Exception e) {
            return BaseResponse.failure("SUMMARY_ERROR", e.getMessage());
        }
    }

    // ============== 下载某天的 JSON 文件 ==============

    @GetMapping("/download/{date}")
    public ResponseEntity<org.springframework.core.io.Resource> download(@PathVariable String date) {
        File file = dayFile(date);
        if (!file.exists()) {
            return ResponseEntity.notFound().build();
        }
        FileSystemResource res = new FileSystemResource(file);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"quick-task-history-" + date + ".json\"")
                .header(HttpHeaders.CONTENT_TYPE, "application/json; charset=UTF-8")
                .contentLength(file.length())
                .body(res);
    }

    // ============== 清空某天 ==============

    @DeleteMapping("/day/{date}")
    public Response<Void> clearDay(@PathVariable String date) {
        File file = dayFile(date);
        if (file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
        return BaseResponse.success(null);
    }

    // ============== 内部方法 ==============

    private File historyDir() {
        File dir = new File(System.getProperty("user.home"),
                ".loong-ai-diagnosis" + File.separator + "quick-task-history");
        return dir;
    }

    private File dayFile(String date) {
        return new File(historyDir(), date + ".json");
    }

    private List<HistoryRecord> loadDayRecords(File file) throws IOException {
        if (!file.exists()) return new ArrayList<>();
        byte[] bytes = Files.readAllBytes(file.toPath());
        if (bytes.length == 0) return new ArrayList<>();
        return mapper.readValue(bytes, new TypeReference<List<HistoryRecord>>() {});
    }

    // ============== DTO ==============

    @Data
    public static class HistoryRecord {
        /** 完整时间戳 yyyy-MM-dd HH:mm:ss.SSS */
        private String timestamp;
        /** 目标服务器地址 */
        private String server;
        /** 任务类型: N2N / N2S / S2N / S2S / GROUP */
        private String taskType;
        /** 模板名称 */
        private String templateName;
        /** 起点 */
        private String startNode;
        /** 终点 */
        private String endNode;
        /** 任务号 */
        private String taskNo;
        /** 组编码 (GROUP 类型) */
        private String groupCode;
        /** 组类型 (GROUP 类型) */
        private String groupType;
        /** 功能列表 */
        private List<String> functions;
        /** 是否成功 */
        private Boolean success;
        /** HTTP 状态码 */
        private Integer httpStatus;
        /** 耗时 ms */
        private Long elapsedMs;
        /** 响应体 (截断, 最多 2000 字符) */
        private String responseBody;
        /** 错误信息 */
        private String errorMessage;
        /** 完整请求 JSON (可选, 方便回溯) */
        private Object requestPayload;
    }

    @Data
    public static class DaySummary {
        private String date;
        private int total;
        private int success;
        private int failed;
        private Map<String, Integer> byType;
        private Map<String, Integer> byServer;
    }
}
