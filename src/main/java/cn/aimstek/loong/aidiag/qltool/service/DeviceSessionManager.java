package cn.aimstek.loong.aidiag.qltool.service;

import cn.aimstek.loong.aidiag.qltool.config.QlToolProperties;
import cn.aimstek.loong.aidiag.qltool.config.QlToolProperties.DeviceConfig;
import cn.aimstek.loong.aidiag.qltool.device.DeviceType;
import cn.aimstek.loong.aidiag.qltool.dto.DeviceItem;
import cn.aimstek.loong.aidiag.qltool.dto.DeviceTreeGroup;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 设备会话管理：从配置 + 持久化 JSON 文件加载设备清单，支持运行时动态增删改。
 *
 * <p>设备来源优先级：application.yml（只读基线） + ql-tool-devices.json（运行时可写）。
 * <p>模块自持有会话，不与现有功能共享任何状态；所有 S7 连接均懒加载。
 */
@Slf4j
@Service
public class DeviceSessionManager {

    private final QlToolProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** deviceId -> 配置（合并 yml + json） */
    private final Map<String, DeviceConfig> configMap = new LinkedHashMap<>();
    /** deviceId -> 会话（懒创建） */
    private final Map<String, DeviceSession> sessions = new ConcurrentHashMap<>();
    /** Prevent an in-flight polling request from recreating an explicitly retired session. */
    private final Set<String> disconnectedDevices = ConcurrentHashMap.newKeySet();

    /** 运行时持久化文件路径（与 jar 同目录） */
    private Path persistFile;

    public DeviceSessionManager(QlToolProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        // 1. 从 yml 加载基线设备
        for (DeviceConfig d : properties.getDevices()) {
            if (d.getDeviceId() != null) {
                // Keep the immutable YAML baseline separate so edits can be detected and persisted.
                configMap.put(d.getDeviceId(), copyConfig(d));
            }
        }
        // 2. 从 JSON 文件加载运行时追加的设备
        persistFile = resolvePersistFile();
        loadFromFile();
        log.info("[青龙调试工具] 载入设备 {} 台（yml + 持久化）", configMap.size());
    }

    // ==================== 设备 CRUD ====================

    /** 添加设备（运行时） */
    public synchronized DeviceItem addDevice(DeviceConfig config) {
        if (config.getDeviceId() == null || config.getDeviceId().isBlank()) {
            config.setDeviceId(UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        }
        if (configMap.containsKey(config.getDeviceId())) {
            throw new IllegalArgumentException("设备ID已存在: " + config.getDeviceId());
        }
        configMap.put(config.getDeviceId(), config);
        saveToFile();
        return toItem(config);
    }

    /** 更新设备配置（运行时，需先断开连接） */
    public synchronized DeviceItem updateDevice(String deviceId, DeviceConfig updated) {
        DeviceConfig existing = requireConfig(deviceId);
        // 如果有活跃连接先断开
        disconnectedDevices.add(deviceId);
        DeviceSession s = sessions.remove(deviceId);
        if (s != null) {
            s.disconnect();
        }
        // 更新字段
        if (updated.getDeviceName() != null) existing.setDeviceName(updated.getDeviceName());
        if (updated.getDeviceType() != null) existing.setDeviceType(updated.getDeviceType());
        if (updated.getIp() != null) existing.setIp(updated.getIp());
        if (updated.getPort() > 0) existing.setPort(updated.getPort());
        existing.setRack(updated.getRack());
        existing.setSlot(updated.getSlot());
        if (updated.getProtocol() != null) existing.setProtocol(updated.getProtocol());
        if (updated.getConnectionType() != null) existing.setConnectionType(updated.getConnectionType());
        saveToFile();
        return toItem(existing);
    }

    /** 删除设备（运行时） */
    public synchronized void removeDevice(String deviceId) {
        requireConfig(deviceId);
        // 断开并移除会话
        disconnectedDevices.add(deviceId);
        DeviceSession s = sessions.remove(deviceId);
        if (s != null) {
            s.disconnect();
        }
        configMap.remove(deviceId);
        disconnectedDevices.remove(deviceId);
        saveToFile();
    }

    // ==================== 查询 ====================

    /** 设备树（按类型分组） */
    public List<DeviceTreeGroup> getDeviceTree() {
        Map<String, DeviceTreeGroup> groups = new LinkedHashMap<>();
        for (DeviceConfig d : configMap.values()) {
            DeviceType type = DeviceType.from(d.getDeviceType());
            DeviceTreeGroup group = groups.computeIfAbsent(type.name(), k -> {
                DeviceTreeGroup g = new DeviceTreeGroup();
                g.setGroupType(type.name());
                g.setGroupName(type.getLabel());
                return g;
            });
            group.getDevices().add(toItem(d));
        }
        return new ArrayList<>(groups.values());
    }

    /** 单设备信息 */
    public DeviceItem getDeviceItem(String deviceId) {
        DeviceConfig d = requireConfig(deviceId);
        return toItem(d);
    }

    public DeviceConfig getConfig(String deviceId) {
        return requireConfig(deviceId);
    }

    /** 获取或创建会话 */
    public DeviceSession session(String deviceId) {
        DeviceConfig d = requireConfig(deviceId);
        if (disconnectedDevices.contains(deviceId)) {
            throw new IllegalStateException("设备已断开，请先连接");
        }
        return sessions.computeIfAbsent(deviceId,
                k -> new DeviceSession(d, properties.getReadTimeoutMs()));
    }

    public void connect(String deviceId) {
        requireConfig(deviceId);
        disconnectedDevices.remove(deviceId);
        DeviceSession s = session(deviceId);
        try {
            s.connect();
        } catch (RuntimeException e) {
            disconnectedDevices.add(deviceId);
            sessions.remove(deviceId, s);
            s.disconnect();
            throw e;
        }
    }

    public void disconnect(String deviceId) {
        requireConfig(deviceId);
        disconnectedDevices.add(deviceId);
        DeviceSession s = sessions.remove(deviceId);
        if (s != null) {
            s.disconnect();
        }
    }

    public boolean isConnected(String deviceId) {
        DeviceSession s = sessions.get(deviceId);
        return s != null && s.isConnected();
    }

    // ==================== 内部 ====================

    private DeviceItem toItem(DeviceConfig d) {
        DeviceType type = DeviceType.from(d.getDeviceType());
        DeviceItem item = new DeviceItem();
        item.setDeviceId(d.getDeviceId());
        item.setDeviceName(d.getDeviceName());
        item.setDeviceType(type.name());
        item.setDeviceTypeLabel(type.getLabel());
        item.setIp(d.getIp());
        item.setPort(d.getPort());
        item.setRack(d.getRack());
        item.setSlot(d.getSlot());
        item.setProtocol(d.getProtocol());
        item.setConnectionType(d.getConnectionType());
        item.setConnected(isConnected(d.getDeviceId()));
        return item;
    }

    private DeviceConfig requireConfig(String deviceId) {
        DeviceConfig d = configMap.get(deviceId);
        if (d == null) {
            throw new IllegalArgumentException("设备不存在: " + deviceId);
        }
        return d;
    }

    // ==================== JSON 持久化 ====================

    private Path resolvePersistFile() {
        // 优先放到 jar 同目录的 data/ 下；IDE 开发环境则在 working dir
        Path dataDir = Paths.get("data");
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            log.warn("[青龙调试工具] 创建 data 目录失败，持久化将不可用: {}", e.getMessage());
        }
        return dataDir.resolve("ql-tool-devices.json");
    }

    private void loadFromFile() {
        if (!Files.exists(persistFile)) {
            return;
        }
        try {
            List<DeviceConfig> list = objectMapper.readValue(
                    persistFile.toFile(), new TypeReference<List<DeviceConfig>>() {});
            for (DeviceConfig d : list) {
                if (d.getDeviceId() != null) {
                    // Persisted runtime values override the YAML baseline.
                    configMap.put(d.getDeviceId(), d);
                }
            }
            log.info("[青龙调试工具] 从持久化文件加载 {} 台运行时设备", list.size());
        } catch (IOException e) {
            log.warn("[青龙调试工具] 读取持久化文件失败: {}", e.getMessage());
        }
    }

    private void saveToFile() {
        // 只保存非 yml 来源的设备（与 yml 基线取差集）
        Set<String> ymlIds = new HashSet<>();
        for (DeviceConfig d : properties.getDevices()) {
            if (d.getDeviceId() != null) ymlIds.add(d.getDeviceId());
        }
        List<DeviceConfig> runtimeDevices = new ArrayList<>();
        for (Map.Entry<String, DeviceConfig> e : configMap.entrySet()) {
            if (!ymlIds.contains(e.getKey())) {
                runtimeDevices.add(e.getValue());
            } else {
                // yml 设备如果被修改了也保存（覆盖）
                DeviceConfig original = properties.getDevices().stream()
                        .filter(d -> e.getKey().equals(d.getDeviceId())).findFirst().orElse(null);
                if (original != null && !configEquals(original, e.getValue())) {
                    runtimeDevices.add(e.getValue());
                }
            }
        }
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(persistFile.toFile(), runtimeDevices);
        } catch (IOException ex) {
            log.warn("[青龙调试工具] 保存持久化文件失败: {}", ex.getMessage());
        }
    }

    private boolean configEquals(DeviceConfig a, DeviceConfig b) {
        return Objects.equals(a.getDeviceName(), b.getDeviceName())
                && Objects.equals(a.getDeviceType(), b.getDeviceType())
                && Objects.equals(a.getIp(), b.getIp())
                && a.getPort() == b.getPort()
                && a.getRack() == b.getRack()
                && a.getSlot() == b.getSlot()
                && Objects.equals(a.getProtocol(), b.getProtocol())
                && Objects.equals(a.getConnectionType(), b.getConnectionType());
    }

    private DeviceConfig copyConfig(DeviceConfig source) {
        DeviceConfig copy = new DeviceConfig();
        copy.setDeviceId(source.getDeviceId());
        copy.setDeviceName(source.getDeviceName());
        copy.setDeviceType(source.getDeviceType());
        copy.setIp(source.getIp());
        copy.setPort(source.getPort());
        copy.setRack(source.getRack());
        copy.setSlot(source.getSlot());
        copy.setProtocol(source.getProtocol());
        copy.setConnectionType(source.getConnectionType());
        copy.setControllerType(source.getControllerType());
        return copy;
    }
}
