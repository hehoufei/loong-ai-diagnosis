package cn.aimstek.loong.aidiag.storagetask;

import cn.aimstek.loong.aidiag.storagetask.dto.StorageTaskRunnerState;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理多个巷道的 StorageTaskRunner 实例.
 * 每个巷道独立运行、独立状态文件、独立线程.
 */
@Slf4j
@Component
public class StorageTaskRunnerManager {

    private final ObjectMapper mapper;
    private final Map<Integer, StorageTaskRunner> runners = new ConcurrentHashMap<>();

    @Autowired
    public StorageTaskRunnerManager(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @PostConstruct
    public void init() {
        // 迁移旧数据: 如果存在无巷道后缀的旧文件, 重命名为 aisle8
        migrateOldFiles();

        // 扫描已有的 state 文件, 自动加载对应巷道的 Runner
        File dir = homeDir();
        File[] files = dir.listFiles((d, name) -> name.matches("storage-task-state-aisle\\d+\\.json"));
        if (files != null) {
            for (File f : files) {
                String name = f.getName();
                // 提取巷道号
                String num = name.replaceAll("\\D+", "");
                try {
                    int aisle = Integer.parseInt(num);
                    getOrCreateRunner(aisle);
                } catch (NumberFormatException e) {
                    log.warn("无法解析巷道号: {}", name);
                }
            }
        }

        log.info("StorageTaskRunnerManager 初始化完成, 已加载 {} 个巷道: {}",
                runners.size(), runners.keySet());
    }

    @PreDestroy
    public void shutdown() {
        for (StorageTaskRunner runner : runners.values()) {
            runner.shutdown();
        }
    }

    /**
     * 获取指定巷道的 Runner, 不存在则创建并初始化.
     */
    public StorageTaskRunner getOrCreateRunner(int aisle) {
        return runners.computeIfAbsent(aisle, a -> {
            StorageTaskRunner r = new StorageTaskRunner(a, mapper);
            r.init();
            log.info("创建巷道 {} 的 Runner", a);
            return r;
        });
    }

    /**
     * 获取指定巷道的 Runner, 不存在返回 null.
     */
    public StorageTaskRunner getRunner(int aisle) {
        return runners.get(aisle);
    }

    /**
     * 获取所有已加载的巷道号列表.
     */
    public List<Integer> getLoadedAisles() {
        return new ArrayList<>(runners.keySet());
    }

    /**
     * 总览: 返回所有巷道的状态摘要.
     */
    public List<AisleOverview> getOverview() {
        List<AisleOverview> list = new ArrayList<>();
        for (Map.Entry<Integer, StorageTaskRunner> entry : runners.entrySet()) {
            StorageTaskRunner r = entry.getValue();
            StorageTaskRunnerState s = r.getState();
            AisleOverview o = new AisleOverview();
            o.setAisle(entry.getKey());
            o.setStatus(s.getStatus().name());
            o.setCurrentRound(s.getCurrentRound());
            o.setCurrentStepInRound(s.getCurrentStepInRound());
            o.setTotalCodes(s.getValidCodes() != null ? s.getValidCodes().size() : 0);
            o.setVisitedCodes(s.getVisitedCodes() != null ? s.getVisitedCodes().size() : 0);
            o.setTotalIssued(s.getTotalIssued());
            o.setTotalSuccess(s.getTotalSuccess());
            o.setTotalFailed(s.getTotalFailed());
            o.setErrorMessage(s.getErrorMessage());
            if (o.getTotalCodes() > 0) {
                o.setProgress(Math.round(o.getVisitedCodes() * 1000.0 / o.getTotalCodes()) / 10.0);
            }
            list.add(o);
        }
        list.sort((a, b) -> Integer.compare(a.getAisle(), b.getAisle()));
        return list;
    }

    /**
     * 迁移旧的无巷道后缀文件为 aisle8.
     */
    private void migrateOldFiles() {
        File dir = homeDir();
        Map<String, String> migrations = new LinkedHashMap<>();
        migrations.put("storage-task-config.json", "storage-task-config-aisle8.json");
        migrations.put("storage-task-state.json", "storage-task-state-aisle8.json");
        migrations.put("storage-task-history.csv", "storage-task-history-aisle8.csv");

        for (Map.Entry<String, String> entry : migrations.entrySet()) {
            File oldFile = new File(dir, entry.getKey());
            File newFile = new File(dir, entry.getValue());
            if (oldFile.exists() && !newFile.exists()) {
                if (oldFile.renameTo(newFile)) {
                    log.info("迁移旧文件: {} -> {}", entry.getKey(), entry.getValue());
                } else {
                    log.warn("迁移旧文件失败: {} -> {}", entry.getKey(), entry.getValue());
                }
            }
        }
    }

    private File homeDir() {
        File dir = new File(System.getProperty("user.home"), ".loong-ai-diagnosis");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    @Data
    public static class AisleOverview {
        private int aisle;
        private String status;
        private int currentRound;
        private int currentStepInRound;
        private int totalCodes;
        private int visitedCodes;
        private long totalIssued;
        private long totalSuccess;
        private long totalFailed;
        private String errorMessage;
        private double progress; // 百分比 0~100
    }
}
