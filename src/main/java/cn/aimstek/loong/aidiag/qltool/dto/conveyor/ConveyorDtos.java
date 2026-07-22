package cn.aimstek.loong.aidiag.qltool.dto.conveyor;

import lombok.Data;

import java.util.List;

/**
 * 输送线各表数据 DTO 集合（对应 LoongAssist.Device.Conveyor.Data）。
 */
public final class ConveyorDtos {

    private ConveyorDtos() {}

    /** 电气能力(容量)表 PlcConfig (DB4001) */
    @Data
    public static class Capacities {
        private int pointSize;
        private int taskSize;
        private int traceSize;
        private int requestSize;
        private int shapeSize;
        private int actionSize;
        private int defaultTraceSize;
    }

    /** 点位状态 NodeState (DB4007) */
    @Data
    public static class NodeStateRow {
        private int index;
        private int pointCode;
        private int pointState;
        private String pointStateLabel;
        private long taskNo;
        private int occupancyData;
        private String occupancyDataLabel;
        private int occupancyState;
        private String occupancyStateLabel;
        private int sensorStatus;
        private int transStatus;
        private int alarmCode;
        private String alarmCodeLabel;
    }

    /** 输送任务 TransTaskInfo (DB4003) */
    @Data
    public static class TransTaskRow {
        private int index;
        private int sysLocker;
        private String sysLockerLabel;
        private long taskNo;
        private int taskType;
        private String taskTypeLabel;
        private int taskParam;
        private int startPoint;
        private int endPoint;
        private long userData;
        private int updateTimes;
    }

    /** 输送轨迹 TransTraceInfo (DB4002) */
    @Data
    public static class TransTraceRow {
        private int index;
        private int sysLocker;
        private String sysLockerLabel;
        private long taskNo;
        private long userData;
        private List<Integer> tracePoints;
        private int updateTimes;
    }

    /** 输送任务状态 TransTaskState (DB4004) */
    @Data
    public static class TransTaskStateRow {
        private int index;
        private long taskNo;
        private int taskType;
        private String taskTypeLabel;
        private int taskParam;
        private int startPoint;
        private int endPoint;
        private long userData;
        private int updateTimes;
        private int taskState;
        private String taskStateLabel;
    }

    /** 单机任务 StandTaskInfo (DB4005) */
    @Data
    public static class StandTaskRow {
        private int index;
        private int sysLocker;
        private String sysLockerLabel;
        private long taskNo;
        private int taskType;
        private String taskTypeLabel;
        private int pointCode;
        private int actionType;
        private String actionTypeLabel;
        private int actionParam1;
        private int actionParam2;
    }

    /** 单机任务状态 StandTaskState (DB4006) */
    @Data
    public static class StandTaskStateRow {
        private int index;
        private long taskNo;
        private int taskType;
        private String taskTypeLabel;
        private int pointCode;
        private int actionType;
        private String actionTypeLabel;
        private int actionParam1;
        private int actionParam2;
        private int taskState;
        private String taskStateLabel;
        private int taskResult1;
        private int taskResult2;
    }

    /** 请求信号 RequestState (DB4008) */
    @Data
    public static class RequestStateRow {
        private int index;
        private int pointCode;
        private int state;
        private int source;
        private int data;
    }

    /** 外形检测 ShapeState (DB4009) */
    @Data
    public static class ShapeStateRow {
        private int index;
        private int pointCode;
        private int detectState;
        private int detectionResultX;
        private int detectionResultY;
        private int detectionResultZ;
        private long detectionData;
    }
}
