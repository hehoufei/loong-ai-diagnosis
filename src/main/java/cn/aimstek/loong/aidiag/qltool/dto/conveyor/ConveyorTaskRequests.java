package cn.aimstek.loong.aidiag.qltool.dto.conveyor;

import lombok.Data;

import java.util.List;

/**
 * 输送线任务下发请求 DTO。
 */
public final class ConveyorTaskRequests {

    private ConveyorTaskRequests() {}

    /** 下发输送任务（同时写轨迹 + 任务，对应 WriteTransTask） */
    @Data
    public static class TransTaskRequest {
        private long taskNo;
        /** 轨迹点序列（最多 50 个） */
        private List<Integer> tracePoints;
        private int startPoint;
        private int endPoint;
        private int taskParam;
        private long userData;
        private int updateTimes;
    }

    /** 下发单机任务（对应 WriteStandTask） */
    @Data
    public static class StandTaskRequest {
        private long taskNo;
        private int pointCode;
        private int actionType;
        private int actionParam1;
        private int actionParam2;
    }

    /** 设置请求信号（对应 SetRequestSignal） */
    @Data
    public static class RequestSignalRequest {
        private int pointCode;
        private int requestState;
    }
}
