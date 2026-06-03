package cn.aimstek.loong.aidiag.storagetask.dto;

import lombok.Data;

/**
 * 单个已下发任务的记录, 用于 UI 展示历史 + 当前任务
 */
@Data
public class StorageTaskRecord {
    /** 序号(全局递增) */
    private long seq;
    /** 第几轮 */
    private int round;
    /** 步骤标签: 入库/移库 1.../出库 */
    private String step;
    /** 任务号 */
    private String taskNo;
    /** 任务类型 N2S / S2S / S2N */
    private String taskType;
    private String startNode;
    private String endNode;
    /** 下发时间 yyyy-MM-dd HH:mm:ss */
    private String submittedAt;
    /** 完成时间 (轮询到终态) */
    private String finishedAt;
    /** 当前状态: 未知/SUCCESS/MANUAL_SUCCESS/CANCEL/FAIL/STUCK 等 */
    private String state;
    /** 任务在 sc_task 表中查询到的 task_state 原值 */
    private String dbTaskState;
    /** 备注/错误信息 */
    private String remark;
    /** 是否需要人工处理 (轮询超过阈值) */
    private boolean stuck;
    /** 任务执行期间堆垛机报警次数 (按 报警类型+编码+首次报警时间 去重计数) */
    private int alarmCount;
    /**
     * 任务执行期间堆垛机报警明细 (取 alarmMessage, 去重保留出现顺序).
     * 用于 UI 悬浮/展开查看具体报了什么.
     */
    private java.util.List<String> alarmMessages = new java.util.ArrayList<>();
}
