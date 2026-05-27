package cn.aimstek.loong.aidiag.storagetask;

import cn.aimstek.loong.aidiag.storagetask.dto.StorageTaskConfig;
import cn.aimstek.loong.aidiag.storagetask.dto.StorageTaskRecord;
import cn.aimstek.loong.aidiag.storagetask.dto.StorageTaskRunnerState;
import cn.aimstek.loong.aidiag.storagetask.dto.StorageTaskRunnerState.Status;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;

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

    public StorageTaskRunner(int aisle, ObjectMapper mapper) {
        this.aisle = aisle;
        ObjectMapper m = mapper.copy();
        m.enable(SerializationFeature.INDENT_OUTPUT);
        this.mapper = m;
    }

    public synchronized void init() {
        loadConfig();
        // 确保 config 中的 aisles 只包含本巷道
        config.setAisles(new ArrayList<>(List.of(aisle)));
        loadState();
        // 进程重启后, 重新进入 PAUSED 状态, 由用户决定是否继续
        if (state.getStatus() == Status.RUNNING) {
            state.setStatus(Status.PAUSED);
            saveState();
        }
    }

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
     * 启动运行. 必须先调用 regenerateValidCodes 生成有效库位.
     * IDLE/FINISHED -> 从头开始 (要求 validCodes 已存在)
     * PAUSED/ERROR  -> 原地继续
     */
    public synchronized void start() {
        if (state.getStatus() == Status.RUNNING) {
            throw new IllegalStateException("已在运行中");
        }
        if (workerThread != null && workerThread.isAlive()) {
            throw new IllegalStateException("工作线程仍在运行, 请稍后再试");
        }

        if (state.getStatus() != Status.PAUSED && state.getStatus() != Status.ERROR) {
            // 从头开始: 不再自动 regenerate, 要求用户先点 "重生成库位"
            if (state.getValidCodes() == null || state.getValidCodes().isEmpty()) {
                throw new IllegalStateException("请先点 '重生成库位' 生成有效库位列表");
            }
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

        workerThread = new Thread(this::runLoop, "storage-task-runner-aisle" + aisle);
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
            StorageTaskRecord justFinished = rec;
            archiveCurrent();
            advanceStep(justFinished);
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
        state.setCursorDirection(1);
        state.setCurrentHoldPosition(null);
        state.setCurrentTask(null);
        state.setRecentTasks(new ArrayList<>());
        state.setValidCodes(new ArrayList<>());
        state.setVisitedCodes(new ArrayList<>());
        state.setCandidateCount(0);
        state.setValidCodesGeneratedAt(null);
        state.setGlobalSeq(0);
        state.setTotalIssued(0);
        state.setTotalSuccess(0);
        state.setTotalManualSuccess(0);
        state.setTotalCanceled(0);
        state.setTotalFailed(0);
        state.setErrorMessage(null);
        saveState();
    }

    /** 重新加载有效库位 (同时保留进度, 根据 currentHoldPosition 重新定位 cursor) */
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
        state.setCandidateCount(all.size());
        state.setValidCodesGeneratedAt(now());

        // 根据 currentHoldPosition 在新列表中重新定位 cursor, 保证续跑不错位
        String hold = state.getCurrentHoldPosition();
        if (hold != null && !valid.isEmpty()) {
            int newIdx = valid.indexOf(hold);
            if (newIdx >= 0) {
                state.setCurrentCursor(newIdx);
                state.setCurrentStartIdx(newIdx);
                log.info("重生成库位后, 根据 holdPosition={} 重新定位 cursor={}", hold, newIdx);
            } else {
                log.warn("重生成库位后, holdPosition={} 在新列表中不存在, cursor 保持不变", hold);
            }
        }

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

    /** 清空已访问库位标记 (不影响其他进度状态) */
    public synchronized void clearVisited() {
        state.setVisitedCodes(new ArrayList<>());
        saveState();
    }

    /**
     * 重试当前未完成的任务 (用户在 ACS 处理掉冲突后调用).
     *  - 仅当 status=PAUSED 且 currentTask 存在
     *  - 不变更 taskNo, 直接调 addTask 重新下发; 成功则启动 worker 继续轮询
     *  - 失败则保持 PAUSED, 错误信息更新
     */
    public synchronized void retryCurrent() {
        if (state.getStatus() != Status.PAUSED && state.getStatus() != Status.ERROR) {
            throw new IllegalStateException("仅在暂停/错误状态可以重试当前任务");
        }
        StorageTaskRecord rec = state.getCurrentTask();
        if (rec == null) {
            throw new IllegalStateException("当前没有未完成的任务可重试");
        }
        if (workerThread != null && workerThread.isAlive()) {
            throw new IllegalStateException("工作线程仍在运行, 请稍后再试");
        }

        // 同步重新下发一次
        StorageAcsClient acs = new StorageAcsClient(config, mapper);
        try {
            acs.addTask(rec.getTaskNo(), rec.getTaskType(),
                    rec.getStartNode(), rec.getEndNode(), rec.getRemark());
        } catch (Exception e) {
            rec.setState("ADD_FAILED");
            rec.setRemark(e.getMessage());
            state.setStatus(Status.PAUSED);
            state.setErrorMessage("重试失败: " + e.getMessage());
            saveState();
            throw new RuntimeException("重试失败: " + e.getMessage(), e);
        }

        // 重试成功: 任务已下发, 计入 totalIssued, 状态改 ISSUED, 重启 worker 进入轮询
        rec.setState("ISSUED");
        rec.setRemark((rec.getRemark() == null ? "" : rec.getRemark()) + " | 已手动重试");
        state.setTotalIssued(state.getTotalIssued() + 1);
        state.setErrorMessage(null);
        pauseRequested = false;
        skipCurrentRequested = false;
        stopRequested = false;
        state.setStatus(Status.RUNNING);
        saveState();

        workerThread = new Thread(this::runLoop, "storage-task-runner-aisle" + aisle);
        workerThread.setDaemon(true);
        workerThread.start();
    }

    /**
     * 预览未来 N 个任务 (干跑, 不下发, 不修改状态)
     * 起点状态: 复制当前 state 作为起点, 但不写回
     */
    public synchronized List<StorageTaskRecord> previewTasks(int n) {
        if (n <= 0) n = 1;
        if (n > 2000) n = 2000;
        List<String> codes = state.getValidCodes();
        if (codes == null || codes.isEmpty()) {
            throw new IllegalStateException("没有有效库位, 请先重生成");
        }

        // 起始位置: 优先用当前 cursor + currentHoldPosition; 否则用 startIdx
        int round = state.getCurrentRound() <= 0 ? 1 : state.getCurrentRound();
        int stepInRound = state.getCurrentStepInRound();
        int startIdx = state.getCurrentStartIdx() % codes.size();
        int cursor = state.getCurrentCursor() % codes.size();
        // 预览不修改真实 state, 用本地的方向变量
        int dir = state.getCursorDirection() == -1 ? -1 : 1;
        String hold = state.getCurrentHoldPosition();
        if (hold == null && stepInRound != 0) {
            hold = codes.get(startIdx);
        }

        List<StorageTaskRecord> out = new java.util.ArrayList<>(n);
        long seq = 0;
        int shuffleN = config.getShuffleTimesPerRound();
        boolean conveyor = config.isEnableConveyorStep();
        int outboundStep = 1 + shuffleN;
        int totalSteps = conveyor ? (1 + shuffleN + 2) : (1 + shuffleN + 1);

        for (int i = 0; i < n; i++) {
            // 一轮新开始
            if (stepInRound == 0 && hold == null) {
                cursor = startIdx;
            }

            StorageTaskRecord rec = new StorageTaskRecord();
            seq++;
            rec.setSeq(seq);
            rec.setRound(round);

            if (stepInRound == 0) {
                String pos = codes.get(startIdx % codes.size());
                rec.setStep("入库");
                rec.setTaskType("N2S");
                rec.setStartNode(config.getInboundStartNode());
                rec.setEndNode(pos);
                hold = pos;
                cursor = startIdx % codes.size();
            } else if (stepInRound <= shuffleN) {
                int curIdx = codes.indexOf(hold);
                if (curIdx < 0) curIdx = cursor;
                String strategy = config.getMoveStrategy() == null ? "LOCAL" : config.getMoveStrategy().toUpperCase();
                String next = pickMoveTarget(codes, curIdx, hold, strategy, config.getMoveLocalWindow());
                rec.setStep("移库 " + stepInRound + "/" + shuffleN);
                rec.setTaskType("S2S");
                rec.setStartNode(hold);
                rec.setEndNode(next);
                hold = next;
                cursor = codes.indexOf(next);
                if (cursor < 0) cursor = 0;
            } else if (stepInRound == outboundStep) {
                // 出库 -> 出库口
                rec.setStep("出库");
                rec.setTaskType("S2N");
                rec.setStartNode(hold);
                rec.setEndNode(config.getOutboundEndNode());
                hold = null;
            } else {
                // 输送 N2N: 出库口 -> 入库口
                rec.setStep("输送");
                rec.setTaskType("N2N");
                rec.setStartNode(config.getOutboundEndNode());
                rec.setEndNode(config.getInboundStartNode());
            }

            rec.setRemark("(预览)");
            rec.setState("PREVIEW");
            out.add(rec);

            stepInRound++;
            if (stepInRound >= totalSteps) {
                // 下一轮起始: 紧贴上一轮位置 + 碰边反转 (与 advanceCursorWithBounce 同逻辑)
                int curForNext = (hold == null) ? cursor : codes.indexOf(hold);
                if (curForNext < 0) curForNext = cursor;
                int nextIdx;
                int nN = codes.size();
                if (nN <= 1) {
                    nextIdx = 0;
                } else {
                    nextIdx = curForNext + dir;
                    if (nextIdx >= nN) {
                        dir = -1;
                        nextIdx = Math.max(0, curForNext - 1);
                    } else if (nextIdx < 0) {
                        dir = 1;
                        nextIdx = Math.min(nN - 1, curForNext + 1);
                    }
                }
                startIdx = nextIdx;
                stepInRound = 0;
                hold = null;
                round++;
            }
        }
        return out;
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
                    // 一轮结束 (理论上不会到这里, planNextStep 在 step>=totalSteps 时才返 null)
                    int next = advanceCursorWithBounce(state.getCurrentCursor(), state.getValidCodes().size());
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
                        // 下发失败: 保留 currentTask, 进入 PAUSED, 用户可点 重试当前 / 跳过当前
                        rec.setState("ADD_FAILED");
                        rec.setRemark(e.getMessage());
                        // 此次下发失败不应该计入 totalIssued (回退)
                        state.setTotalIssued(Math.max(0, state.getTotalIssued() - 1));
                        state.setStatus(Status.PAUSED);
                        state.setErrorMessage("addTask 失败: " + e.getMessage()
                                + " ── 处理掉冲突后, 点 重试当前 重新下发, 或点 跳过当前");
                        saveState();
                        log.warn("addTask 失败, 进入 PAUSED 等待人工: {}", e.getMessage());
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
                    StorageTaskRecord justFinished = rec;
                    archiveCurrent();
                    advanceStep(justFinished);
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
                StorageTaskRecord justFinished = rec;
                archiveCurrent();
                advanceStep(justFinished);
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
        int shuffleN = config.getShuffleTimesPerRound();
        boolean conveyor = config.isEnableConveyorStep();
        // step 索引: 0=入库, 1..N=移库, N+1=出库, N+2=输送(可选)
        int outboundStep = 1 + shuffleN;             // N+1
        int conveyorStep = outboundStep + 1;         // N+2
        int totalSteps = conveyor ? (1 + shuffleN + 2) : (1 + shuffleN + 1);
        if (step >= totalSteps) return null;

        StepPlan plan = new StepPlan();
        if (step == 0) {
            // 入库: 顺序滚动 (从上一轮 endCursor+1 开始, 物理上靠近上一轮最后位置)
            String pos = codes.get(state.getCurrentStartIdx() % n);
            plan.taskType = "N2S";
            plan.startNode = config.getInboundStartNode();
            plan.endNode = pos;
            plan.stepLabel = "入库";
            plan.remark = "R" + state.getCurrentRound() + "-入库";
        } else if (step <= shuffleN) {
            // 移库: 按 moveStrategy 选目标
            String cur = state.getCurrentHoldPosition();
            int curIdx = state.getCurrentCursor();
            if (cur == null) {
                curIdx = state.getCurrentStartIdx() % n;
                cur = codes.get(curIdx);
            } else {
                // 重新校准 cursor 防止偏差
                int found = codes.indexOf(cur);
                if (found >= 0) curIdx = found;
            }
            String next;
            if (n == 1) {
                next = cur;
            } else {
                String strategy = config.getMoveStrategy() == null ? "LOCAL" : config.getMoveStrategy().toUpperCase();
                next = pickMoveTarget(codes, curIdx, cur, strategy, config.getMoveLocalWindow());
            }
            plan.taskType = "S2S";
            plan.startNode = cur;
            plan.endNode = next;
            plan.stepLabel = "移库 " + step + "/" + shuffleN;
            plan.remark = "R" + state.getCurrentRound() + "-移库" + step + "/" + shuffleN;
        } else if (step == outboundStep) {
            // 出库 -> 出库口 ND_11002
            String cur = state.getCurrentHoldPosition();
            if (cur == null) cur = codes.get(state.getCurrentStartIdx() % n);
            plan.taskType = "S2N";
            plan.startNode = cur;
            plan.endNode = config.getOutboundEndNode();
            plan.stepLabel = "出库";
            plan.remark = "R" + state.getCurrentRound() + "-出库";
        } else {
            // 输送 N2N: 出库口 ND_11002 -> 入库口 ND_11001
            plan.taskType = "N2N";
            plan.startNode = config.getOutboundEndNode();
            plan.endNode = config.getInboundStartNode();
            plan.stepLabel = "输送";
            plan.remark = "R" + state.getCurrentRound() + "-输送";
        }
        return plan;
    }

    /**
     * 推进状态. 必须在 archiveCurrent 之前调用 (因为 archive 会清空 currentTask).
     * 现在改成接收一个刚完成的任务记录, 从中读取 endNode 来更新状态.
     */
    private void advanceStep(StorageTaskRecord justFinished) {
        int step = state.getCurrentStepInRound();
        int shuffleN = config.getShuffleTimesPerRound();
        boolean conveyor = config.isEnableConveyorStep();
        int outboundStep = 1 + shuffleN;          // N+1
        int totalSteps = conveyor ? (1 + shuffleN + 2) : (1 + shuffleN + 1);
        List<String> codes = state.getValidCodes();
        int n = codes.size();

        String endNode = justFinished == null ? null : justFinished.getEndNode();

        if (step == 0) {
            // 入库后, 持有刚刚的 endNode
            state.setCurrentHoldPosition(endNode);
            state.setCurrentCursor(indexOfOrFallback(codes, endNode, state.getCurrentStartIdx()));
        } else if (step <= shuffleN) {
            // 移库后, 持有目标库位
            state.setCurrentHoldPosition(endNode);
            state.setCurrentCursor(indexOfOrFallback(codes, endNode, state.getCurrentCursor()));
        } else if (step == outboundStep) {
            // 出库后, 货物到了 ND_11002, 不再持有库位
            state.setCurrentHoldPosition(null);
        }
        // 输送 step (== outboundStep+1) 不持有库位也不更新 cursor

        state.setCurrentStepInRound(step + 1);
        if (state.getCurrentStepInRound() >= totalSteps) {
            // 一轮结束: 下一轮 startIdx 紧贴上一轮最后位置, 碰边反弹
            int next = n == 0 ? 0 : advanceCursorWithBounce(state.getCurrentCursor(), n);
            state.setCurrentStartIdx(next);
            state.setCurrentStepInRound(0);
            state.setCurrentTask(null);
        } else {
            state.setCurrentTask(null);
        }
    }

    private static int indexOfOrFallback(List<String> codes, String code, int fallback) {
        if (code == null) return fallback;
        int i = codes.indexOf(code);
        return i >= 0 ? i : fallback;
    }

    /**
     * cursor 推进 1 步 (来回循环).
     * 碰到 0 / n-1 边界自动反转方向, 不会跨整库回头.
     * 副作用: 更新 state.cursorDirection.
     */
    private int advanceCursorWithBounce(int cursor, int n) {
        if (n <= 1) return 0;
        int dir = state.getCursorDirection();
        if (dir != 1 && dir != -1) dir = 1;
        int next = cursor + dir;
        if (next >= n) {
            // 碰到右边界, 反转
            dir = -1;
            next = Math.max(0, cursor - 1);
        } else if (next < 0) {
            // 碰到左边界, 反转
            dir = 1;
            next = Math.min(n - 1, cursor + 1);
        }
        state.setCursorDirection(dir);
        return next;
    }

    /**
     * 选移库目标: 纯顺序取下一个.
     * 因为 validCodes 的生成顺序已保证相邻库位跨侧 (ROW_VISIT_ORDER={1,3,2,4}),
     * 不需要运行时做任何 avoid 判断, 直接取 codes[(curIdx+1) % n] 即可.
     */
    private String pickMoveTarget(List<String> codes, int curIdx, String cur, String strategy, int window) {
        int n = codes.size();
        if (n <= 1) return cur;
        int next = (curIdx + 1) % n;
        // 防止极端情况下起终点相同
        if (codes.get(next).equals(cur)) {
            next = (next + 1) % n;
        }
        return codes.get(next);
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

        // 记录已访问库位 (任务真正到达终态: SUCCESS / MANUAL_SUCCESS / SKIPPED 都算访问过)
        String s = rec.getState() == null ? "" : rec.getState().toUpperCase();
        if ("SUCCESS".equals(s) || "MANUAL_SUCCESS".equals(s) || "SKIPPED".equals(s)) {
            markVisited(rec.getStartNode());
            markVisited(rec.getEndNode());
        }

        // 持久化到 CSV (含失败 / 跳过 / 卡住状态, 都记一笔)
        StorageHistoryWriter.append(historyFile(), rec);

        LinkedList<StorageTaskRecord> tasks = new LinkedList<>(
                state.getRecentTasks() == null ? new ArrayList<>() : state.getRecentTasks());
        tasks.addFirst(rec);
        while (tasks.size() > 200) {
            tasks.removeLast();
        }
        state.setRecentTasks(new ArrayList<>(tasks));
        state.setCurrentTask(null);
    }

    /** 把库位编码加入 visitedCodes (跳过 null / 入库口节点 ND_xxx) */
    private void markVisited(String node) {
        if (node == null || node.isBlank()) return;
        if (!node.startsWith("SL_")) return;   // 入库口 ND_xxx 不算
        List<String> visited = state.getVisitedCodes();
        if (visited == null) {
            visited = new ArrayList<>();
            state.setVisitedCodes(visited);
        }
        if (!visited.contains(node)) {
            visited.add(node);
        }
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

    private final int aisle;

    private File configFile() {
        return new File(homeDir(), "storage-task-config-aisle" + aisle + ".json");
    }

    private File stateFile() {
        return new File(homeDir(), "storage-task-state-aisle" + aisle + ".json");
    }

    public File historyFile() {
        return new File(homeDir(), "storage-task-history-aisle" + aisle + ".csv");
    }

    public int getAisle() { return aisle; }

    /** 清空 CSV 历史 (不影响 state / 进度) */
    public synchronized void clearHistoryCsv() {
        File f = historyFile();
        if (f.exists()) //noinspection ResultOfMethodCallIgnored
            f.delete();
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
                    state.setVisitedCodes(loaded.getVisitedCodes() == null ? new ArrayList<>() : loaded.getVisitedCodes());
                    state.setCandidateCount(loaded.getCandidateCount());
                    state.setValidCodesGeneratedAt(loaded.getValidCodesGeneratedAt());
                    state.setCurrentRound(loaded.getCurrentRound());
                    state.setCurrentStepInRound(loaded.getCurrentStepInRound());
                    state.setCurrentStartIdx(loaded.getCurrentStartIdx());
                    state.setCurrentCursor(loaded.getCurrentCursor());
                    state.setCursorDirection(loaded.getCursorDirection() == -1 ? -1 : 1);
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
