package cn.aimstek.loong.aidiag.qltool.service;

import cn.aimstek.loong.aidiag.qltool.config.QlToolProperties;
import cn.aimstek.loong.aidiag.qltool.config.QlToolProperties.DeviceConfig;
import cn.aimstek.loong.aidiag.qltool.device.DeviceType;
import cn.aimstek.loong.aidiag.qltool.dto.DeviceItem;
import cn.aimstek.loong.aidiag.qltool.dto.DeviceTreeGroup;
import cn.aimstek.loong.aidiag.qltool.dto.SiteItem;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 设备会话管理：从配置 + 持久化 JSON 文件加载设备清单，支持运行时动态增删改。
 *
 * <p>首次启动从 application.yml 初始化；之后以用户目录中的 devices.json 完整快照为准。
 * <p>模块自持有会话，不与现有功能共享任何状态；所有 S7 连接均懒加载。
 */
@Slf4j
@Service
public class DeviceSessionManager {

    public static final String DEFAULT_SITE_NAME = "恒申美达";

    private final QlToolProperties properties;
    private final QlToolDataStore dataStore;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** deviceId -> 配置（合并 yml + json） */
    private final Map<String, DeviceConfig> configMap = new LinkedHashMap<>();
    /** 按创建顺序保存现场；允许现场暂时没有设备。 */
    private final Set<String> sites = new LinkedHashSet<>();
    /** deviceId -> 会话（懒创建） */
    private final Map<String, DeviceSession> sessions = new ConcurrentHashMap<>();
    /** Prevent an in-flight polling request from recreating an explicitly retired session. */
    private final Set<String> disconnectedDevices = ConcurrentHashMap.newKeySet();

    public DeviceSessionManager(QlToolProperties properties, QlToolDataStore dataStore) {
        this.properties = properties;
        this.dataStore = dataStore;
    }

    @PostConstruct
    public void init() {
        configMap.clear();
        sites.clear();
        QlToolDataStore.SiteSnapshot siteSnapshot = dataStore.loadSites();
        if (siteSnapshot.exists()) {
            siteSnapshot.sites().stream()
                    .map(this::normalizeSiteName)
                    .forEach(sites::add);
        }
        QlToolDataStore.DeviceSnapshot snapshot = dataStore.loadDevices();
        if (snapshot.exists()) {
            for (DeviceConfig d : snapshot.devices()) {
                if (d.getDeviceId() != null && !d.getDeviceId().isBlank()) {
                    d.setSiteName(normalizeSiteName(d.getSiteName()));
                    sites.add(d.getSiteName());
                    configMap.put(d.getDeviceId(), copyConfig(d));
                }
            }
            ensureDefaultSite();
            saveSites();
            log.info("[青龙调试工具] 从固定数据目录恢复设备 {} 台", configMap.size());
            return;
        }

        // 首次运行以 yml 为初始值。
        for (DeviceConfig d : properties.getDevices()) {
            if (d.getDeviceId() != null) {
                d.setSiteName(normalizeSiteName(d.getSiteName()));
                sites.add(d.getSiteName());
                configMap.put(d.getDeviceId(), copyConfig(d));
            }
        }

        // 兼容旧版本相对路径 data/ql-tool-devices.json，迁移后统一保存完整快照。
        int migrated = loadLegacyOverrides();
        configMap.values().forEach(d -> {
            d.setSiteName(normalizeSiteName(d.getSiteName()));
            sites.add(d.getSiteName());
        });
        ensureDefaultSite();
        saveToFile();
        saveSites();
        log.info("[青龙调试工具] 初始化设备 {} 台（其中旧文件迁移 {} 台）", configMap.size(), migrated);
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
        config.setSiteName(normalizeSiteName(config.getSiteName()));
        sites.add(config.getSiteName());
        configMap.put(config.getDeviceId(), config);
        saveToFile();
        saveSites();
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
        if (updated.getSiteName() != null) {
            existing.setSiteName(normalizeSiteName(updated.getSiteName()));
            sites.add(existing.getSiteName());
        }
        if (updated.getDeviceType() != null) existing.setDeviceType(updated.getDeviceType());
        if (updated.getIp() != null) existing.setIp(updated.getIp());
        if (updated.getPort() > 0) existing.setPort(updated.getPort());
        existing.setRack(updated.getRack());
        existing.setSlot(updated.getSlot());
        if (updated.getProtocol() != null) existing.setProtocol(updated.getProtocol());
        if (updated.getConnectionType() != null) existing.setConnectionType(updated.getConnectionType());
        saveToFile();
        saveSites();
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

    public synchronized List<SiteItem> getSites() {
        ensureDefaultSite();
        return sites.stream().map(site -> {
            int stackers = 0;
            int conveyors = 0;
            for (DeviceConfig device : configMap.values()) {
                if (!site.equals(normalizeSiteName(device.getSiteName()))) continue;
                DeviceType type = DeviceType.from(device.getDeviceType());
                if (type == DeviceType.STACKER_CRANE) stackers++;
                if (type == DeviceType.CONVEYOR_LINE) conveyors++;
            }
            return new SiteItem(site, stackers + conveyors, stackers, conveyors);
        }).toList();
    }

    public synchronized SiteItem addSite(String siteName) {
        String normalized = requireSiteName(siteName);
        if (!sites.add(normalized)) {
            throw new IllegalArgumentException("现场已存在: " + normalized);
        }
        saveSites();
        return getSites().stream().filter(site -> site.getSiteName().equals(normalized)).findFirst().orElseThrow();
    }

    public synchronized SiteItem renameSite(String oldName, String newName) {
        String oldNormalized = requireSiteName(oldName);
        String newNormalized = requireSiteName(newName);
        if (!sites.contains(oldNormalized)) throw new IllegalArgumentException("现场不存在: " + oldNormalized);
        if (!oldNormalized.equals(newNormalized) && sites.contains(newNormalized)) {
            throw new IllegalArgumentException("现场已存在: " + newNormalized);
        }
        LinkedHashSet<String> renamed = new LinkedHashSet<>();
        sites.forEach(site -> renamed.add(site.equals(oldNormalized) ? newNormalized : site));
        sites.clear();
        sites.addAll(renamed);
        configMap.values().stream()
                .filter(device -> oldNormalized.equals(normalizeSiteName(device.getSiteName())))
                .forEach(device -> device.setSiteName(newNormalized));
        saveToFile();
        saveSites();
        return getSites().stream().filter(site -> site.getSiteName().equals(newNormalized)).findFirst().orElseThrow();
    }

    public synchronized void removeSite(String siteName) {
        String normalized = requireSiteName(siteName);
        boolean hasDevice = configMap.values().stream()
                .anyMatch(device -> normalized.equals(normalizeSiteName(device.getSiteName())));
        if (hasDevice) throw new IllegalArgumentException("现场下仍有设备，请先移动或删除设备");
        if (!sites.remove(normalized)) throw new IllegalArgumentException("现场不存在: " + normalized);
        ensureDefaultSite();
        saveSites();
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
        item.setSiteName(normalizeSiteName(d.getSiteName()));
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

    private int loadLegacyOverrides() {
        Path legacyFile = Paths.get("data", "ql-tool-devices.json");
        if (!Files.exists(legacyFile)) return 0;
        try {
            List<DeviceConfig> list = objectMapper.readValue(
                    legacyFile.toFile(), new TypeReference<List<DeviceConfig>>() {});
            for (DeviceConfig d : list) {
                if (d.getDeviceId() != null && !d.getDeviceId().isBlank()) {
                    configMap.put(d.getDeviceId(), copyConfig(d));
                }
            }
            log.info("[青龙调试工具] 已迁移旧设备文件 {}，共 {} 台", legacyFile.toAbsolutePath(), list.size());
            return list.size();
        } catch (Exception e) {
            log.warn("[青龙调试工具] 读取旧设备文件失败，将使用 yml 初始值: {}", e.getMessage());
            return 0;
        }
    }

    private void saveToFile() {
        List<DeviceConfig> devices = configMap.values().stream()
                .map(this::copyConfig)
                .toList();
        dataStore.saveDevices(devices);
    }

    private void saveSites() {
        dataStore.saveSites(new ArrayList<>(sites));
    }

    private void ensureDefaultSite() {
        if (sites.isEmpty()) sites.add(DEFAULT_SITE_NAME);
    }

    private String normalizeSiteName(String siteName) {
        return siteName == null || siteName.isBlank() ? DEFAULT_SITE_NAME : siteName.trim();
    }

    private String requireSiteName(String siteName) {
        String normalized = siteName == null ? "" : siteName.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException("现场名称不能为空");
        if (normalized.length() > 40) throw new IllegalArgumentException("现场名称不能超过40个字符");
        return normalized;
    }

    private DeviceConfig copyConfig(DeviceConfig source) {
        DeviceConfig copy = new DeviceConfig();
        copy.setDeviceId(source.getDeviceId());
        copy.setDeviceName(source.getDeviceName());
        copy.setSiteName(normalizeSiteName(source.getSiteName()));
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
