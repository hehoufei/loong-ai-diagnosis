package cn.aimstek.loong.aidiag.qltool.dto.crane;

import lombok.Data;

/**
 * 堆垛机状态快照（对应 LoongAssist CraneStatusModel + TaskResultModel）。
 */
@Data
public class CraneStatusDto {

    // ===== 车体状态 =====
    private int workMode;
    private String workModeLabel;
    private int taskStatus;
    private String taskStatusLabel;
    private long taskNo;
    private int rowStation;
    private int dockState;
    private String dockStateLabel;
    private String alarmMessage;
    private long dockHorizontalPulse;
    private long dockVerticalPulse;

    // ===== 货叉1/2 =====
    private ForkStatusDto fork1;
    private ForkStatusDto fork2;

    // ===== 指令反馈 =====
    private TaskResultDto result;

    /** 刷新时间（服务端读到的时刻） */
    private String refreshTime;

    /**
     * 单个货叉状态。
     */
    @Data
    public static class ForkStatusDto {
        private long pulse;
        private int colStation;
        private int colValid;
        private String colValidLabel;
        private int colBack;
        private String colBackLabel;
        private int hasLoad;
        private String hasLoadLabel;
        private int active;
        private String activeLabel;
    }

    /**
     * 指令反馈。
     */
    @Data
    public static class TaskResultDto {
        private String commandType;
        private String resultType;
        private long taskNo;
        private int resultCode;
    }
}
