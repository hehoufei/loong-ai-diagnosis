package cn.aimstek.loong.aidiag.storagetask;

import cn.aimstek.loong.aidiag.storagetask.dto.StorageTaskConfig;
import cn.aimstek.loong.aidiag.storagetask.dto.StorageTaskRecord;
import cn.aimstek.loong.aidiag.storagetask.dto.StorageTaskRunnerState;
import cn.aimstek.loong.aidiag.storagetask.dto.StorageTaskRunnerState.Status;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/**
 * 立库库位任务循环测试 调度执行器
 *
 * 设计:
 *  - 单线程顺序执行 (一次只下发一个任务, 等其终态后再发下一个)
 *  - 支持 pause / resume / skipCurrent / reset
 *  - 状态/配置/任务历史 持久化到 ~/.loong-ai-diagnosis/, 进程重启可断点续跑
 */
@Slf4j
@Component
public class StorageTaskRunner {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter TASK_NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    /** 视为"已完成 可继续"的终态 */
    private static final Set<String> ADVANCING_STATES = Set.of(
            "SUCCESS", "MANUAL_SUCCESS", "CANCEL", "CANCELED", "CANCELLED"
    );
    /** 视为"任务失败 需要人工介入"的终态 */
    private static final Set<String> FAILED_STATES = Set.of("FAIL", "FAILED");

    private final ObjectMapper mapper;

    private volatile StorageTaskConfig config;
    private final StorageTaskRunnerState state = new StorageTaskRunnerState();

    /** 控制信号 */
    private final Object pauseLock = new Object();
    private volatile boolean pauseRequested = false;
    private volatile boolean skipCurrentRequested = false;
    private volatile boolean stopRequested = false;
    private volatile Thread workerThread;

    @Autowired
    public StorageTaskRunner(ObjectMapper mapper) {
        ObjectMapper m = mapper.copy();
        m.enable(SerializationFeature.INDENT_OUTPUT);
        this.mapper = m;
    }

    @PostConstruct
    public synchronized void init() {
        loadConfig();
        loadState();
        // 进程重启后, 重新进入 PAUSED 状态, 由用户决定是否继续
        if (state.getStatus() == Status.RUNNING) {
            state.setStatus(Status.PAUSED);
            saveState();
        }
    }

    @PreDestroy
    public synchronized void shutdown() {
        stopRequested = true;
        synchronized (pauseLock) {
            pauseLock.notifyAll();
        }
        if (workerThread != null) {
            workerThread.interrupt();
        }
    }

    // ============== 对外 API (Controller 调用) ==============

    public synchronized StorageTaskConfig getConfig() {
        return config;
    }

    public synchronized void updateConfig(StorageTaskConfig newConfig) {
        if (state.getStatus() == Status.RUNNING) {
            throw new IllegalStateException("当前正在运行, 请先暂停后再修改配置");
        }
        // SQL 合法性校验, 错误的 SQL 不让保存
        StorageDb.validateSelectSql("validateLocationSql", newConfig.getValidateLocationSql());
        if (newConfig.getValidateLocationSql() == null
                || !newConfig.getValidateLocationSql().contains(StorageDb.CODES_PLACEHOLDER)) {
            throw new IllegalArgumentException(
                    "validateLocationSql 必须包含占位符 " + StorageDb.CODES_PLACEHOLDER);
        }
        StorageDb.validateSelectSql("queryTaskStateSql", newConfig.getQueryTaskStateSql());
        this.config = newConfig;
        saveConfig();
    }

    public synchronized StorageTaskRunnerState getState() {
        return cloneState();
    }

    /**
     * 启动运行. 如果状态为 IDLE/FINISHED/ERROR 会重新生成有效库位列表;
     * 如果状态为 PAUSED 则原地继续.
     */
    public synchronized void start() {
        if (state.getStatus() == Status.RUNNING) {
            throw new IllegalStateException("已在运行中");
        }
        if (workerThread != null && workerThread.isAlive()) {
            throw new IllegalStateException("工作线程仍在运行, 请稍后再试");
        }

        if (state.getStatus() != Status.PAUSED && state.getStatus() != Status.ERROR) {
            // 从头开始: 重新生成
            regenerateValidCodes();
            state.setCurrentRound(0);
            state.setCurrentStepInRound(0);
            state.setCurrentStartIdx(0);
            state.setCurrentCursor(0);
            state.setCurrentHoldPosition(null);
            state.setCurrentTask(null);
            state.setErrorMessage(null);
        } else {
            // 从 PAUSED/ERROR 恢复
            state.setErrorMessage(null);
        }

        if (state.getValidCodes() == null || state.getValidCodes().isEmpty()) {
            throw new IllegalStateException("没有可用库位, 请检查数据库与配置");
        }

        pauseRequested = false;
        skipCurrentRequested = false;
        stopRequested = false;
        state.setStatus(Status.RUNNING);
        saveState();

        workerThread = new Thread(this::runLoop, "storage-task-runner");
        workerThread.setDaemon(true);
        workerThread.start();
    }

    /** 请求暂停, 工作线程将在当前 step 完成或被打断后停止. */
    public synchronized void pause() {
        if (state.getStatus() != Status.RUNNING) {
            return;
        }
        pauseRequested = true;
        synchronized (pauseLock) {
            pauseLock.notifyAll();
        }
    }

    /**
     * 跳过当前任务. 用于 "我已手动处理了卡住的任务, 直接进入下一步".
     *  - RUNNING: 给工作线程发信号, 退出当前轮询并标记 SKIPPED, 继续下一步
     *  - PAUSED / ERROR: 直接归档并推进, 保持当前状态不动 (用户随后可点继续)
     */
    public synchronized void skipCurrent() {
        if (state.getStatus() == Status.RUNNING) {
            skipCurrentRequested = true;
            synchronized (pauseLock) {
                pauseLock.notifyAll();
            }
            return;
        }
        if (state.getStatus() == Status.PAUSED || state.getStatus() == Status.ERROR) {
            StorageTaskRecord rec = state.getCurrentTask();
            if (rec == null) {
                throw new IllegalStateException("当前没有未完成的任务可跳过");
            }
            rec.setState("SKIPPED");
            rec.setFinishedAt(LocalDateTime.now().format(TS_FMT));
            rec.setStuck(false);
            String existing = rec.getRemark() == null ? "" : rec.getRemark();
            rec.setRemark((existing.isBlank() ? "" : existing + " | ") + "用户手动跳过 db_state=" + rec.getDbTaskState());
            archiveCurrent();
            advanceStep();
            // 错误状态跳过后保持 PAUSED, 让用户决定是否继续
            if (state.getStatus() == Status.ERROR) {
                state.setStatus(Status.PAUSED);
                state.setErrorMessage(null);
            }
            saveState();
            return;
        }
        throw new IllegalStateException("当前状态不允许跳过: " + state.getStatus());
    }

    /** 重置: 清空进度但保留配置 */
    public synchronized void reset() {
        if (state.getStatus() == Status.RUNNING) {
            throw new IllegalStateException("请先暂停再重置");
        }
        state.setStatus(Status.IDLE);
        state.setCurrentRound(0);
        state.setCurrentStepInRound(0);
        state.setCurrentStartIdx(0);
        state.setCurrentCursor(0);
        state.setCurrentHoldPosition(null);
        state.setCurrentTask(null);
        state.setRecentTasks(new ArrayList<>());
        state.setValidCodes(new ArrayList<>());
        state.setGlobalSeq(0);
        state.setTotalIssued(0);
        state.setTotalSuccess(0);
        state.setTotalManualSuccess(0);
        state.setTotalCanceled(0);
        state.setTotalFailed(0);
        state.setErrorMessage(null);
        saveState();
    }

    /** 重新加载有效库位 (同时保留进度) */
    public synchronized void regenerateValidCodes() {
        StorageDb db = new StorageDb(config);
        List<String> all = StorageCodeGenerator.generate(config);
        List<String> valid;
        try {
            valid = db.filterExistingCodes(all);
        } catch (Exception e) {
            throw new RuntimeException("生成/校验库位失败: " + e.getMessage(), e);
        }
        state.setValidCodes(valid);
        saveState();
        log.info("生成候选库位 {}, 数据库内有效 {}", all.size(), valid.size());
    }

    public synchronized void testDb() {
        try {
            new StorageDb(config).ping();
        } catch (Exception e) {
            throw new RuntimeException("数据库连接失败: " + e.getMessage(), e);
        }
    }

    // ============== 主循环 ==============

    private void runLoop() {
        try {
            StorageDb db = new StorageDb(config);
            StorageAcsClient acs = new StorageAcsClient(config, mapper);

            while (!stopRequested) {
                // 检查暂停
                if (pauseRequested) {
                    log.info("收到暂停请求, 进入 PAUSED");
                    setStatus(Status.PAUSED);
                    return;
                }

                // 进入新一轮
                if (state.getCurrentStepInRound() == 0 && state.getCurrentTask() == null) {
                    // 新一轮开始
                    state.setCurrentRound(state.getCurrentRound() + 1);
                    state.setCurrentCursor(state.getCurrentStartIdx());
                    state.setCurrentHoldPosition(null);
                    saveState();
                    log.info("==================== 第 {} 轮 开始 ====================", state.getCurrentRound());
                }

                // 决定本步骤参数
                StepPlan plan = planNextStep();
                if (plan == null) {
                    // 一轮结束
                    int next = (state.getCurrentCursor() + 1) % state.getValidCodes().size();
                    state.setCurrentStartIdx(next);
                    state.setCurrentStepInRound(0);
                    state.setCurrentTask(null);
                    saveState();
                    continue;
                }

                // 下发任务
                if (state.getCurrentTask() == null) {
                    StorageTaskRecord rec = newRecord(plan);
                    state.setCurrentTask(rec);
                    state.setTotalIssued(state.getTotalIssued() + 1);
                    saveState();
                    try {
                        acs.addTask(rec.getTaskNo(), rec.getTaskType(),
                                rec.getStartNode(), rec.getEndNode(), rec.getRemark());
                    } catch (Exception e) {
                        rec.setState("ADD_FAILED");
                        rec.setRemark(e.getMessage());
                        archiveCurrent();
                        state.setStatus(Status.ERROR);
                        state.setErrorMessage("addTask 调用失败: " + e.getMessage());
                        saveState();
                        log.error("addTask 失败, 进入 ERROR: {}", e.getMessage(), e);
                        return;
                    }
                }

                // 轮询直到终态 / 暂停 / 跳过
                String finalState = pollUntilTerminal(db);
                StorageTaskRecord rec = state.getCurrentTask();
                if (rec == null) {
                    continue;
                }

                if ("__PAUSE__".equals(finalState)) {
                    rec.setState("PAUSED");
                    rec.setRemark("用户暂停时未达终态, 当前 db_state=" + rec.getDbTaskState());
                    saveState();
                    setStatus(Status.PAUSED);
                    return;
                }
                if ("__SKIP__".equals(finalState)) {
                    rec.setState("SKIPPED");
                    rec.setFinishedAt(now());
                    rec.setStuck(false);
                    rec.setRemark("用户手动跳过, db_state=" + rec.getDbTaskState());
                    archiveCurrent();
                    advanceStep();
                    saveState();
                    continue;
                }

                rec.setState(finalState);
                rec.setFinishedAt(now());
                rec.setStuck(false);

                if (FAILED_STATES.contains(finalState)) {
                    state.setTotalFailed(state.getTotalFailed() + 1);
                    archiveCurrent();
                    setStatus(Status.PAUSED);
                    state.setErrorMessage("任务终态失败 (" + finalState + "), 已自动暂停, 请人工处理后点击 跳过当前 / 继续");
                    saveState();
                    return;
                }

                // 终态 ok, 推进
                if ("MANUAL_SUCCESS".equals(finalState)) {
                    state.setTotalManualSuccess(state.getTotalManualSuccess() + 1);
                } else if ("CANCEL".equals(finalState) || "CANCELED".equals(finalState) || "CANCELLED".equals(finalState)) {
                    state.setTotalCanceled(state.getTotalCanceled() + 1);
                } else {
                    state.setTotalSuccess(state.getTotalSuccess() + 1);
                }
                archiveCurrent();
                advanceStep();
                saveState();
            }
        } catch (Throwable t) {
            log.error("循环线程异常", t);
            state.setStatus(Status.ERROR);
            state.setErrorMessage("循环异常: " + t.getMessage());
            saveState();
        }
    }

    /**
     * 轮询任务状态:
     *   返回 "SUCCESS" / "MANUAL_SUCCESS" / "CANCEL" / "FAIL" / ...
     *   或特殊标记 "__PAUSE__" / "__SKIP__"
     */
    private String pollUntilTerminal(StorageDb db) {
        StorageTaskRecord rec = state.getCurrentTask();
        long startMs = System.currentTimeMillis();
        long thresholdMs = config.getTaskStuckThresholdSeconds() * 1000L;

        while (true) {
            if (stopRequested) return "__PAUSE__";
            if (skipCurrentRequested) {
                skipCurrentRequested = false;
                return "__SKIP__";
            }
            if (pauseRequested) {
                return "__PAUSE__";
            }

            String dbState = db.queryTaskState(rec.getTaskNo());
            rec.setDbTaskState(dbState);
            if (dbState != null) {
                String upper = dbState.toUpperCase();
                if (ADVANCING_STATES.contains(upper)) {
                    return upper;
                }
                if (FAILED_STATES.contains(upper)) {
                    return upper;
                }
            }

            // 卡住标记
            long elapsed = System.currentTimeMillis() - startMs;
            boolean nowStuck = elapsed > thresholdMs;
            if (nowStuck != rec.isStuck()) {
                rec.setStuck(nowStuck);
                saveState();
            }

            // 等待
            synchronized (pauseLock) {
                try {
                    pauseLock.wait(Math.max(500L, config.getPollIntervalSeconds() * 1000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return "__PAUSE__";
                }
            }
        }
    }

    private StepPlan planNextStep() {
        List<String> codes = state.getValidCodes();
        if (codes == null || codes.isEmpty()) return null;

        int n = codes.size();
        int step = state.getCurrentStepInRound();
        int totalSteps = 1 + config.getShuffleTimesPerRound() + 1; // 入库 + N次移库 + 出库
        if (step >= totalSteps) return null;

        StepPlan plan = new StepPlan();
        if (step == 0) {
            // 入库
            String pos = codes.get(state.getCurrentStartIdx() % n);
            plan.taskType = "N2S";
            plan.startNode = config.getInboundStartNode();
            plan.endNode = pos;
            plan.stepLabel = "入库";
            plan.remark = "R" + (state.getCurrentRound()) + "-入库";
            plan.nextHold = pos;
            plan.nextCursor = state.getCurrentStartIdx();
        } else if (step <= config.getShuffleTimesPerRound()) {
            // 移库 step/SHUFFLE_TIMES_PER_ROUND
            int curCursor = state.getCurrentCursor();
            int nextCursor = (curCursor + 1) % n;
            String next = codes.get(nextCursor);
            String cur = state.getCurrentHoldPosition() != null
                    ? state.getCurrentHoldPosition()
                    : codes.get(curCursor);
            if (next.equals(cur)) {
                nextCursor = (nextCursor + 1) % n;
                next = codes.get(nextCursor);
            }
            plan.taskType = "S2S";
            plan.startNode = cur;
            plan.endNode = next;
            plan.stepLabel = "移库 " + step + "/" + config.getShuffleTimesPerRound();
            plan.remark = "R" + state.getCurrentRound() + "-移库" + step + "/" + config.getShuffleTimesPerRound();
            plan.nextHold = next;
            plan.nextCursor = nextCursor;
        } else {
            // 出库
            String cur = state.getCurrentHoldPosition() != null
                    ? state.getCurrentHoldPosition()
                    : codes.get(state.getCurrentCursor());
            plan.taskType = "S2N";
            plan.startNode = cur;
            plan.endNode = config.getInboundStartNode();
            plan.stepLabel = "出库";
            plan.remark = "R" + state.getCurrentRound() + "-出库";
            plan.nextHold = null;
            plan.nextCursor = state.getCurrentCursor();
        }
        return plan;
    }

    private void advanceStep() {
        StepPlan plan = state.getCurrentTask() == null ? null : null;
        // 实际推进基于刚刚执行的 step
        int step = state.getCurrentStepInRound();
        int totalSteps = 1 + config.getShuffleTimesPerRound() + 1;

        if (step == 0) {
            // 入库后, 持有第一库位
            String pos = state.getValidCodes().get(state.getCurrentStartIdx() % state.getValidCodes().size());
            state.setCurrentHoldPosition(pos);
            state.setCurrentCursor(state.getCurrentStartIdx());
        } else if (step <= config.getShuffleTimesPerRound()) {
            int n = state.getValidCodes().size();
            int nextCursor = (state.getCurrentCursor() + 1) % n;
            String next = state.getValidCodes().get(nextCursor);
            String cur = state.getCurrentHoldPosition();
            if (cur != null && next.equals(cur)) {
                nextCursor = (nextCursor + 1) % n;
                next = state.getValidCodes().get(nextCursor);
            }
            state.setCurrentCursor(nextCursor);
            state.setCurrentHoldPosition(next);
        } else if (step == totalSteps - 1) {
            // 出库后, 不再持有
            state.setCurrentHoldPosition(null);
        }

        state.setCurrentStepInRound(step + 1);
        if (state.getCurrentStepInRound() >= totalSteps) {
            // 一轮结束
            int n = state.getValidCodes().size();
            int next = (state.getCurrentCursor() + 1) % n;
            state.setCurrentStartIdx(next);
            state.setCurrentStepInRound(0);
            state.setCurrentTask(null);
        } else {
            state.setCurrentTask(null);
        }
    }

    private StorageTaskRecord newRecord(StepPlan plan) {
        state.setGlobalSeq(state.getGlobalSeq() + 1);
        StorageTaskRecord rec = new StorageTaskRecord();
        rec.setSeq(state.getGlobalSeq());
        rec.setRound(state.getCurrentRound());
        rec.setStep(plan.stepLabel);
        rec.setTaskNo(genTaskNo());
        rec.setTaskType(plan.taskType);
        rec.setStartNode(plan.startNode);
        rec.setEndNode(plan.endNode);
        rec.setSubmittedAt(now());
        rec.setState("ISSUED");
        rec.setRemark(plan.remark);
        return rec;
    }

    private void archiveCurrent() {
        StorageTaskRecord rec = state.getCurrentTask();
        if (rec == null) return;
        LinkedList<StorageTaskRecord> tasks = new LinkedList<>(
                state.getRecentTasks() == null ? new ArrayList<>() : state.getRecentTasks());
        tasks.addFirst(rec);
        while (tasks.size() > 200) {
            tasks.removeLast();
        }
        state.setRecentTasks(new ArrayList<>(tasks));
        state.setCurrentTask(null);
    }

    private void setStatus(Status status) {
        state.setStatus(status);
        saveState();
    }

    private synchronized StorageTaskRunnerState cloneState() {
        try {
            String json = mapper.writeValueAsString(state);
            return mapper.readValue(json, StorageTaskRunnerState.class);
        } catch (Exception e) {
            return state;
        }
    }

    // ============== 持久化 ==============

    private File configFile() {
        return new File(homeDir(), "storage-task-config.json");
    }

    private File stateFile() {
        return new File(homeDir(), "storage-task-state.json");
    }

    private File homeDir() {
        File dir = new File(System.getProperty("user.home"), ".loong-ai-diagnosis");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    private void loadConfig() {
        File f = configFile();
        if (f.exists()) {
            try {
                config = mapper.readValue(f, StorageTaskConfig.class);
                return;
            } catch (Exception e) {
                log.warn("加载 storage-task 配置失败, 使用默认值: {}", e.getMessage());
            }
        }
        config = new StorageTaskConfig();
        saveConfig();
    }

    private void saveConfig() {
        try {
            mapper.writeValue(configFile(), config);
        } catch (IOException e) {
            log.error("保存 storage-task 配置失败: {}", e.getMessage(), e);
        }
    }

    private void loadState() {
        File f = stateFile();
        if (f.exists()) {
            try {
                StorageTaskRunnerState loaded = mapper.readValue(f, StorageTaskRunnerState.class);
                if (loaded != null) {
                    // 不直接替换字段, 拷贝过来
                    state.setStatus(loaded.getStatus());
                    state.setErrorMessage(loaded.getErrorMessage());
                    state.setValidCodes(loaded.getValidCodes() == null ? new ArrayList<>() : loaded.getValidCodes());
                    state.setCurrentRound(loaded.getCurrentRound());
                    state.setCurrentStepInRound(loaded.getCurrentStepInRound());
                    state.setCurrentStartIdx(loaded.getCurrentStartIdx());
                    state.setCurrentCursor(loaded.getCurrentCursor());
                    state.setCurrentHoldPosition(loaded.getCurrentHoldPosition());
                    state.setCurrentTask(loaded.getCurrentTask());
                    state.setGlobalSeq(loaded.getGlobalSeq());
                    state.setRecentTasks(loaded.getRecentTasks() == null ? new ArrayList<>() : loaded.getRecentTasks());
                    state.setTotalIssued(loaded.getTotalIssued());
                    state.setTotalSuccess(loaded.getTotalSuccess());
                    state.setTotalManualSuccess(loaded.getTotalManualSuccess());
                    state.setTotalCanceled(loaded.getTotalCanceled());
                    state.setTotalFailed(loaded.getTotalFailed());
                }
            } catch (Exception e) {
                log.warn("加载 storage-task 状态失败: {}", e.getMessage());
            }
        }
    }

    private synchronized void saveState() {
        try {
            mapper.writeValue(stateFile(), state);
        } catch (IOException e) {
            log.error("保存 storage-task 状态失败: {}", e.getMessage(), e);
        }
    }

    // ============== 工具 ==============

    private static String now() {
        return LocalDateTime.now().format(TS_FMT);
    }

    private String genTaskNo() {
        String ts = LocalDateTime.now().format(TASK_NO_FMT);
        return config.getTaskNoPrefix() + ts + "_" + String.format("%04d", state.getGlobalSeq());
    }

    private static class StepPlan {
        String taskType;
        String startNode;
        String endNode;
        String stepLabel;
        String remark;
        String nextHold;       // 仅参考, 实际由 advanceStep 计算
        int nextCursor;
    }
}
