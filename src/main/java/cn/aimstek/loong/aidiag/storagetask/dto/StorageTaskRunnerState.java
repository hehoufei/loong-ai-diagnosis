package cn.aimstek.loong.aidiag.storagetask.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 运行时状态(可被 UI 拉取展示, 也用于持久化断点续跑)
 */
@Data
public class StorageTaskRunnerState {

    /** 状态机: IDLE / RUNNING / PAUSED / FINISHED / ERROR */
    public enum Status { IDLE, RUNNING, PAUSED, FINISHED, ERROR }

    private Status status = Status.IDLE;

    /** 错误信息 (status=ERROR 时填充) */
    private String errorMessage;

    /** 已加载的有效库位列表 */
    private List<String> validCodes = new ArrayList<>();

    /**
     * 已经访问过 (任务成功完成) 的库位列表.
     * 重置时清空, 重生成库位时保留 (除非用户明确点重置).
     */
    private List<String> visitedCodes = new ArrayList<>();

    /** 候选库位数 (生成后的总数, DB 校验前) */
    private int candidateCount;

    /** 上次重生成完成时间 yyyy-MM-dd HH:mm:ss */
    private String validCodesGeneratedAt;

    /** 当前轮次 (从 1 开始) */
    private int currentRound;

    /** 当前轮内步骤索引: 0=入库, 1..N=移库, N+1=出库 */
    private int currentStepInRound;

    /** 当前轮起始库位下标 */
    private int currentStartIdx;

    /** 当前轮内已经定位到的库位指针(滚动用) */
    private int currentCursor;

    /**
     * cursor 推进方向 (+1 顺向 / -1 反向).
     * 跑到末尾会自动反转, 实现"段 1→N→1→N..."的来回循环.
     */
    private int cursorDirection = 1;

    /** 当前持有货物的库位 (用于 S2S/S2N 起点) */
    private String currentHoldPosition;

    /** 当前正在执行的任务记录 (未完成) */
    private StorageTaskRecord currentTask;

    /** 任务全局序号 */
    private long globalSeq;

    /** 已完成任务记录 (最近 200 条, 倒序) */
    private List<StorageTaskRecord> recentTasks = new ArrayList<>();

    /** 总下发数 / 总成功数 */
    private long totalIssued;
    private long totalSuccess;
    private long totalManualSuccess;
    private long totalCanceled;
    private long totalFailed;
}
