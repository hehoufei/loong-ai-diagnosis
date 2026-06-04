package cn.aimstek.loong.aidiag.storagetask.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 每日跑库报告数据模型.
 * 一个报告对应一天 + 一个巷道; 也可以汇总为全巷道.
 */
@Data
public class DailyReport {

    /** 统计日期 yyyy-MM-dd */
    private String date;
    /** 巷道号 (0 表示汇总) */
    private int aisle;

    // ===== 任务统计 =====
    private int totalIssued;
    private int totalSuccess;
    private int totalManualSuccess;
    private int totalCancel;
    private int totalFail;
    private int totalSkipped;
    private int totalStuck;

    // ===== 按类型统计 =====
    private int inboundCount;   // N2S
    private int shuffleCount;   // S2S
    private int outboundCount;  // S2N
    private int conveyorCount;  // N2N

    // ===== 耗时统计 (秒) =====
    private Double avgDuration;
    private Integer maxDuration;
    private Integer minDuration;
    private Long totalDuration;

    // ===== 按类型平均耗时 =====
    private Double avgInboundDuration;
    private Double avgShuffleDuration;
    private Double avgOutboundDuration;

    // ===== 覆盖度 =====
    private int totalCodes;
    private int visitedCodes;
    private int newVisitedToday;
    private double coveragePct;

    // ===== 轮次 =====
    private Integer startRound;
    private Integer endRound;
    private int completedRounds;

    // ===== 报警 =====
    private int totalAlarms;

    /** 报警明细: 按报警内容分组统计次数, 频次降序 */
    private List<AlarmStat> alarmBreakdown = new ArrayList<>();

    // ===== 运行时间 =====
    private String runStartTime;
    private String runEndTime;
    private Integer effectiveMinutes;

    // ===== 成功率 =====
    private double successRate;

    // ===== 异常任务清单 (失败/卡住/报警) =====
    private List<AbnormalTask> abnormalTasks = new ArrayList<>();

    @Data
    public static class AbnormalTask {
        private String time;
        private int aisle;
        private String taskNo;
        private String taskType;
        private String issue;
        private String state;
    }

    /** 单种报警的统计 */
    @Data
    public static class AlarmStat {
        /** 报警内容 (alarmMessage) */
        private String message;
        /** 出现次数 (去重后, 跨任务累加) */
        private int count;
        /** 触发该报警的任务数 */
        private int taskCount;
        /** 触发该报警的任务列表 (时间 + 任务号 + 类型) */
        private List<AlarmTaskRef> tasks = new ArrayList<>();
    }

    /** 报警关联的任务简要信息 */
    @Data
    public static class AlarmTaskRef {
        private String taskNo;
        private String taskType;
        private String submittedAt;
        private String startNode;
        private String endNode;
        /** 该任务内此报警出现的次数 */
        private int count;
    }
}
