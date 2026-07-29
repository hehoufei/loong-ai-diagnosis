package cn.aimstek.loong.aidiag.qltool.service;

import cn.aimstek.loong.aidiag.qltool.config.QlToolProperties.DeviceConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 青龙调试工具的跨版本数据存储。
 *
 * <p>数据固定保存到 {@code ${user.home}/.loong-ai-diagnosis/ql-tool}，
 * 不依赖 JAR、MSI 安装目录或浏览器缓存，因此替换程序包后仍可加载。
 */
@Slf4j
@Service
public class QlToolDataStore {

    private final ObjectMapper mapper;
    private Path rootDir;

    public QlToolDataStore(ObjectMapper objectMapper) {
        this.mapper = objectMapper.copy().findAndRegisterModules();
    }

    @PostConstruct
    public void init() {
        rootDir = Path.of(System.getProperty("user.home"), ".loong-ai-diagnosis", "ql-tool");
        try {
            Files.createDirectories(rootDir.resolve("layouts"));
            Files.createDirectories(rootDir.resolve("delete-history"));
            log.info("[青龙调试工具] 数据目录: {}", rootDir.toAbsolutePath());
        } catch (IOException e) {
            log.warn("[青龙调试工具] 创建数据目录失败: {}", e.getMessage());
        }
    }

    public synchronized DeviceSnapshot loadDevices() {
        Path file = devicesFile();
        if (!Files.exists(file)) {
            return new DeviceSnapshot(false, new ArrayList<>());
        }
        try {
            List<DeviceConfig> devices = mapper.readValue(
                    file.toFile(), new TypeReference<List<DeviceConfig>>() {});
            return new DeviceSnapshot(true, devices == null ? new ArrayList<>() : devices);
        } catch (IOException e) {
            log.warn("[青龙调试工具] 读取设备配置失败: {}", e.getMessage());
            throw new IllegalStateException("读取设备配置失败: " + e.getMessage(), e);
        }
    }

    public synchronized void saveDevices(List<DeviceConfig> devices) {
        writeJson(devicesFile(), devices == null ? List.of() : devices);
    }

    public synchronized SiteSnapshot loadSites() {
        Path file = sitesFile();
        if (!Files.exists(file)) {
            return new SiteSnapshot(false, new ArrayList<>());
        }
        try {
            List<String> sites = mapper.readValue(file.toFile(), new TypeReference<List<String>>() {});
            return new SiteSnapshot(true, sites == null ? new ArrayList<>() : sites);
        } catch (IOException e) {
            log.warn("[青龙调试工具] 读取现场配置失败: {}", e.getMessage());
            throw new IllegalStateException("读取现场配置失败: " + e.getMessage(), e);
        }
    }

    public synchronized void saveSites(List<String> sites) {
        writeJson(sitesFile(), sites == null ? List.of() : sites);
    }

    public synchronized LayoutSnapshot loadLayout(String deviceId) {
        Path file = layoutFile(deviceId);
        if (!Files.exists(file)) {
            return new LayoutSnapshot(false, mapper.createArrayNode());
        }
        try {
            JsonNode value = mapper.readTree(file.toFile());
            return new LayoutSnapshot(true, value != null && value.isArray() ? value : mapper.createArrayNode());
        } catch (IOException e) {
            log.warn("[青龙调试工具] 读取设备 {} 布局失败: {}", deviceId, e.getMessage());
            throw new IllegalStateException("读取布局失败: " + e.getMessage(), e);
        }
    }

    public synchronized void saveLayout(String deviceId, JsonNode layout) {
        if (layout == null || !layout.isArray()) {
            throw new IllegalArgumentException("布局必须是 JSON 数组");
        }
        if (layout.size() > 20_000) {
            throw new IllegalArgumentException("布局点位数量超过上限");
        }
        writeJson(layoutFile(deviceId), layout);
    }

    public synchronized void clearLayout(String deviceId) {
        writeJson(layoutFile(deviceId), mapper.createArrayNode());
    }

    public synchronized List<DeleteTaskRecord> loadDeleteHistory(String deviceId) {
        Path file = deleteHistoryFile(deviceId);
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        try {
            return mapper.readValue(file.toFile(), new TypeReference<List<DeleteTaskRecord>>() {});
        } catch (IOException e) {
            log.warn("[青龙调试工具] 读取设备 {} 删除记录失败: {}", deviceId, e.getMessage());
            throw new IllegalStateException("读取删除记录失败: " + e.getMessage(), e);
        }
    }

    /**
     * 审计写入不能反向改变 PLC 操作结果；落盘失败只记录日志。
     */
    public synchronized void appendDeleteRecord(String deviceId, long taskNo, String pointCode,
                                                String source, String action, boolean success, String message) {
        try {
            List<DeleteTaskRecord> records = loadDeleteHistory(deviceId);
            records.add(0, new DeleteTaskRecord(
                    UUID.randomUUID().toString(),
                    OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                    deviceId,
                    taskNo,
                    pointCode == null ? "" : pointCode,
                    source == null || source.isBlank() ? "UNKNOWN" : source,
                    action,
                    success ? "SUCCESS" : "FAILED",
                    message == null ? "" : message
            ));
            writeJson(deleteHistoryFile(deviceId), records);
        } catch (Exception e) {
            log.warn("[青龙调试工具] 保存设备 {} 任务 {} 删除记录失败: {}", deviceId, taskNo, e.getMessage());
        }
    }

    public synchronized int clearDeleteHistory(String deviceId) {
        List<DeleteTaskRecord> records = loadDeleteHistory(deviceId);
        int count = records.size();
        writeJson(deleteHistoryFile(deviceId), List.of());
        return count;
    }

    private Path layoutFile(String deviceId) {
        return rootDir.resolve("layouts").resolve(safeDeviceId(deviceId) + ".json");
    }

    private Path devicesFile() {
        return rootDir.resolve("devices.json");
    }

    private Path sitesFile() {
        return rootDir.resolve("sites.json");
    }

    private Path deleteHistoryFile(String deviceId) {
        return rootDir.resolve("delete-history").resolve(safeDeviceId(deviceId) + ".json");
    }

    private String safeDeviceId(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            throw new IllegalArgumentException("设备 ID 不能为空");
        }
        return deviceId.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private void writeJson(Path target, Object value) {
        try {
            Files.createDirectories(target.getParent());
            Path temp = target.resolveSibling(target.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), value);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("保存文件失败: " + target.toAbsolutePath() + "，" + e.getMessage(), e);
        }
    }

    public record LayoutSnapshot(boolean exists, JsonNode layout) {}

    public record DeviceSnapshot(boolean exists, List<DeviceConfig> devices) {}

    public record SiteSnapshot(boolean exists, List<String> sites) {}

    public record DeleteTaskRecord(
            String id,
            String deletedAt,
            String deviceId,
            long taskNo,
            String pointCode,
            String source,
            String action,
            String result,
            String message
    ) {}
}
