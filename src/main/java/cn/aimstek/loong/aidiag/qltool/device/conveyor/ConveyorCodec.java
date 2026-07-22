package cn.aimstek.loong.aidiag.qltool.device.conveyor;

import cn.aimstek.loong.aidiag.qltool.codec.S7Reader;
import cn.aimstek.loong.aidiag.qltool.codec.S7Writer;
import cn.aimstek.loong.aidiag.qltool.dto.conveyor.ConveyorDtos.*;
import cn.aimstek.loong.aidiag.qltool.dto.conveyor.ConveyorTaskRequests.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 输送线二进制编解码（对应 LoongAssist.Device.Conveyor.Metadata + MetadataExtension）。
 * PLC 大端序：读写均按 BIG_ENDIAN。
 */
public final class ConveyorCodec {

    private ConveyorCodec() {}

    // ===== DB 地址与记录大小 =====
    public static final int DB_CONFIG = 4001;    public static final int SIZE_CONFIG = 14;
    public static final int DB_TRACE = 4002;     public static final int SIZE_TRACE = 112;
    public static final int DB_TASK = 4003;      public static final int SIZE_TASK = 20;
    public static final int DB_TASK_STATE = 4004; public static final int SIZE_TASK_STATE = 20;
    public static final int DB_STAND = 4005;     public static final int SIZE_STAND = 16;
    public static final int DB_STAND_STATE = 4006; public static final int SIZE_STAND_STATE = 20;
    public static final int DB_NODE = 4007;      public static final int SIZE_NODE = 18;
    public static final int DB_REQUEST = 4008;   public static final int SIZE_REQUEST = 8;
    public static final int DB_SHAPE = 4009;     public static final int SIZE_SHAPE = 14;

    /** 上位机控制锁标志 */
    public static final byte[] LOCK_FLAG = {0x00, 0x02};
    public static final byte[] UNLOCK_FLAG = {0x00, 0x01};
    public static final int SYS_LOCK = 2;
    public static final int TASK_TYPE_ADD = 1;
    public static final int TASK_TYPE_REMOVE = 3;
    public static final int STAND_TASK_ADD = 1;

    // ===== 能力表 =====
    public static Capacities decodeCapacities(byte[] data) {
        S7Reader r = new S7Reader(data);
        Capacities c = new Capacities();
        c.setTraceSize(r.u2());
        c.setTaskSize(r.u2());
        c.setRequestSize(r.u2());
        c.setShapeSize(r.u2());
        c.setPointSize(r.u2());
        c.setActionSize(r.u2());
        c.setDefaultTraceSize(r.u2());
        return c;
    }

    // ===== 点位状态 =====
    public static List<NodeStateRow> decodeNodeStates(byte[] data, int count) {
        List<NodeStateRow> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            S7Reader r = new S7Reader(data, i * SIZE_NODE);
            NodeStateRow n = new NodeStateRow();
            n.setIndex(i);
            n.setPointCode(r.u2());
            int ps = r.u2(); n.setPointState(ps); n.setPointStateLabel(ConveyorEnums.pointState(ps));
            n.setTaskNo(r.u4());
            int od = r.u2(); n.setOccupancyData(od); n.setOccupancyDataLabel(ConveyorEnums.blockingState(od));
            int os = r.u2(); n.setOccupancyState(os); n.setOccupancyStateLabel(ConveyorEnums.occupancyState(os));
            n.setSensorStatus(r.u2());
            n.setTransStatus(r.u2());
            int ac = r.u2(); n.setAlarmCode(ac); n.setAlarmCodeLabel(ConveyorEnums.pointAlarm(ac));
            list.add(n);
        }
        return list;
    }

    // ===== 输送任务 =====
    public static List<TransTaskRow> decodeTransTasks(byte[] data, int count) {
        List<TransTaskRow> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            S7Reader r = new S7Reader(data, i * SIZE_TASK);
            TransTaskRow t = new TransTaskRow();
            t.setIndex(i);
            int sl = r.u2(); t.setSysLocker(sl); t.setSysLockerLabel(ConveyorEnums.sysLocker(sl));
            t.setTaskNo(r.u4());
            int tt = r.u2(); t.setTaskType(tt); t.setTaskTypeLabel(ConveyorEnums.taskType(tt));
            t.setTaskParam(r.u2());
            t.setStartPoint(r.u2());
            t.setEndPoint(r.u2());
            t.setUserData(r.u4());
            t.setUpdateTimes(r.u2());
            list.add(t);
        }
        return list;
    }

    // ===== 输送轨迹 =====
    public static List<TransTraceRow> decodeTransTraces(byte[] data, int count) {
        List<TransTraceRow> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            S7Reader r = new S7Reader(data, i * SIZE_TRACE);
            TransTraceRow t = new TransTraceRow();
            t.setIndex(i);
            int sl = r.u2(); t.setSysLocker(sl); t.setSysLockerLabel(ConveyorEnums.sysLocker(sl));
            t.setTaskNo(r.u4());
            t.setUserData(r.u4());
            List<Integer> pts = new ArrayList<>();
            for (int p = 0; p < 50; p++) {
                int v = r.u2();
                if (v != 0) pts.add(v);
            }
            t.setTracePoints(pts);
            t.setUpdateTimes(r.u2());
            list.add(t);
        }
        return list;
    }

    // ===== 输送任务状态 =====
    public static List<TransTaskStateRow> decodeTransTaskStates(byte[] data, int count) {
        List<TransTaskStateRow> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            S7Reader r = new S7Reader(data, i * SIZE_TASK_STATE);
            TransTaskStateRow t = new TransTaskStateRow();
            t.setIndex(i);
            t.setTaskNo(r.u4());
            int tt = r.u2(); t.setTaskType(tt); t.setTaskTypeLabel(ConveyorEnums.taskType(tt));
            t.setTaskParam(r.u2());
            t.setStartPoint(r.u2());
            t.setEndPoint(r.u2());
            t.setUserData(r.u4());
            t.setUpdateTimes(r.u2());
            int ts = r.u2(); t.setTaskState(ts); t.setTaskStateLabel(ConveyorEnums.taskState(ts));
            list.add(t);
        }
        return list;
    }

    // ===== 单机任务 =====
    public static List<StandTaskRow> decodeStandTasks(byte[] data, int count) {
        List<StandTaskRow> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            S7Reader r = new S7Reader(data, i * SIZE_STAND);
            StandTaskRow t = new StandTaskRow();
            t.setIndex(i);
            int sl = r.u2(); t.setSysLocker(sl); t.setSysLockerLabel(ConveyorEnums.sysLocker(sl));
            t.setTaskNo(r.u4());
            int tt = r.u2(); t.setTaskType(tt); t.setTaskTypeLabel(ConveyorEnums.standTaskType(tt));
            t.setPointCode(r.u2());
            int at = r.u2(); t.setActionType(at); t.setActionTypeLabel(ConveyorEnums.actionType(at));
            t.setActionParam1(r.u2());
            t.setActionParam2(r.u2());
            list.add(t);
        }
        return list;
    }

    // ===== 单机任务状态 =====
    public static List<StandTaskStateRow> decodeStandTaskStates(byte[] data, int count) {
        List<StandTaskStateRow> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            S7Reader r = new S7Reader(data, i * SIZE_STAND_STATE);
            StandTaskStateRow t = new StandTaskStateRow();
            t.setIndex(i);
            t.setTaskNo(r.u4());
            int tt = r.u2(); t.setTaskType(tt); t.setTaskTypeLabel(ConveyorEnums.standTaskType(tt));
            t.setPointCode(r.u2());
            int at = r.u2(); t.setActionType(at); t.setActionTypeLabel(ConveyorEnums.actionType(at));
            t.setActionParam1(r.u2());
            t.setActionParam2(r.u2());
            int ts = r.u2(); t.setTaskState(ts); t.setTaskStateLabel(ConveyorEnums.taskState(ts));
            t.setTaskResult1(r.u2());
            t.setTaskResult2(r.u2());
            list.add(t);
        }
        return list;
    }

    // ===== 请求信号 =====
    public static List<RequestStateRow> decodeRequestStates(byte[] data, int count) {
        List<RequestStateRow> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            S7Reader r = new S7Reader(data, i * SIZE_REQUEST);
            RequestStateRow t = new RequestStateRow();
            t.setIndex(i);
            t.setPointCode(r.u2());
            t.setState(r.u2());
            t.setSource(r.u2());
            t.setData(r.u2());
            list.add(t);
        }
        return list;
    }

    // ===== 外形检测 =====
    public static List<ShapeStateRow> decodeShapeStates(byte[] data, int count) {
        List<ShapeStateRow> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            S7Reader r = new S7Reader(data, i * SIZE_SHAPE);
            ShapeStateRow t = new ShapeStateRow();
            t.setIndex(i);
            t.setPointCode(r.u2());
            t.setDetectState(r.u2());
            t.setDetectionResultX(r.u2());
            t.setDetectionResultY(r.u2());
            t.setDetectionResultZ(r.u2());
            t.setDetectionData(r.u4());
            list.add(t);
        }
        return list;
    }

    // ===== 编码：输送任务（20B）=====
    public static byte[] encodeTransTask(long taskNo, int taskType, int taskParam,
                                         int startPoint, int endPoint, long userData, int updateTimes) {
        return new S7Writer(SIZE_TASK)
                .u2(SYS_LOCK)
                .u4(taskNo)
                .u2(taskType)
                .u2(taskParam)
                .u2(startPoint)
                .u2(endPoint)
                .u4(userData)
                .u2(updateTimes)
                .toBytes();
    }

    // ===== 编码：输送轨迹（112B）=====
    public static byte[] encodeTransTrace(long taskNo, long userData, int updateTimes, List<Integer> tracePoints) {
        S7Writer w = new S7Writer(SIZE_TRACE)
                .u2(SYS_LOCK)
                .u4(taskNo)
                .u4(userData);
        for (int i = 0; i < 50; i++) {
            int p = (tracePoints != null && i < tracePoints.size()) ? tracePoints.get(i) : 0;
            w.u2(p);
        }
        w.u2(updateTimes);
        return w.toBytes();
    }

    // ===== 编码：单机任务（16B）=====
    public static byte[] encodeStandTask(long taskNo, int taskType, int pointCode,
                                         int actionType, int actionParam1, int actionParam2) {
        return new S7Writer(SIZE_STAND)
                .u2(SYS_LOCK)
                .u4(taskNo)
                .u2(taskType)
                .u2(pointCode)
                .u2(actionType)
                .u2(actionParam1)
                .u2(actionParam2)
                .toBytes();
    }

    // ===== 编码：请求信号（8B）=====
    public static byte[] encodeRequestSignal(int pointCode, int requestState) {
        int source = requestState == 0 ? 0 : 2;
        return new S7Writer(SIZE_REQUEST)
                .u2(pointCode)
                .u2(requestState)
                .u2(source)
                .u2(0)
                .toBytes();
    }
}
