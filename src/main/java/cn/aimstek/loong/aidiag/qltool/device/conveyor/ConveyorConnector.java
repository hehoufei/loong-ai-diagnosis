package cn.aimstek.loong.aidiag.qltool.device.conveyor;

import cn.aimstek.loong.aidiag.qltool.dto.conveyor.ConveyorDtos.*;
import cn.aimstek.loong.aidiag.qltool.service.DeviceSession;
import cn.aimstek.loong.aidiag.qltool.transport.S7Client;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * 输送线连接器（对应 LoongAssist.Device.Conveyor.ConveyorConnector）。
 *
 * <p>能力(容量)表读一次缓存；各表按容量条数读取；大块读自动按 PDU 上限分帧。
 * 写任务复刻"找空槽→加锁→写数据→解锁"三步握手。所有读写在 session 串行。
 */
public class ConveyorConnector {

    /**
     * 单次向 s7connector 请求的字节上限。设大值让底层 libnodave 按"协商 PDU"自动分帧，
     * 在 S7-1500（PDU 480/960）上电报数最少、最快；不再人为按 200B 碎片化。
     */
    private static final int CHUNK = 8192;

    private final DeviceSession session;
    private volatile Capacities capacities;

    public ConveyorConnector(DeviceSession session) {
        this.session = session;
    }

    public boolean uses(DeviceSession candidate) {
        return this.session == candidate;
    }

    private S7Client s7() {
        return session.getS7();
    }

    /** 读能力(容量)表，并缓存 */
    public synchronized Capacities capacities() {
        byte[] buf = s7().readDataBlock(ConveyorCodec.DB_CONFIG, 0, ConveyorCodec.SIZE_CONFIG);
        this.capacities = ConveyorCodec.decodeCapacities(buf);
        return this.capacities;
    }

    private Capacities ensureCapacities() {
        if (capacities == null) {
            capacities();
        }
        return capacities;
    }

    /** 分帧读取，规避单次请求超 PDU */
    private byte[] readChunked(int db, int total) {
        if (total <= 0) {
            return new byte[0];
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(total);
        int offset = 0;
        while (offset < total) {
            int len = Math.min(CHUNK, total - offset);
            byte[] part = s7().readDataBlock(db, offset, len);
            out.write(part, 0, part.length);
            offset += len;
        }
        return out.toByteArray();
    }

    // ===== 读各表 =====
    public synchronized List<NodeStateRow> readNodeStates() {
        int count = ensureCapacities().getPointSize();
        byte[] buf = readChunked(ConveyorCodec.DB_NODE, count * ConveyorCodec.SIZE_NODE);
        return ConveyorCodec.decodeNodeStates(buf, count);
    }

    public synchronized List<TransTaskRow> readTransTasks() {
        int count = ensureCapacities().getTaskSize();
        byte[] buf = readChunked(ConveyorCodec.DB_TASK, count * ConveyorCodec.SIZE_TASK);
        return ConveyorCodec.decodeTransTasks(buf, count);
    }

    public synchronized List<TransTraceRow> readTransTraces() {
        int count = ensureCapacities().getTraceSize();
        byte[] buf = readChunked(ConveyorCodec.DB_TRACE, count * ConveyorCodec.SIZE_TRACE);
        return ConveyorCodec.decodeTransTraces(buf, count);
    }

    public synchronized List<TransTaskStateRow> readTransTaskStates() {
        int count = ensureCapacities().getTaskSize();
        byte[] buf = readChunked(ConveyorCodec.DB_TASK_STATE, count * ConveyorCodec.SIZE_TASK_STATE);
        return ConveyorCodec.decodeTransTaskStates(buf, count);
    }

    public synchronized List<StandTaskRow> readStandTasks() {
        int count = ensureCapacities().getActionSize();
        byte[] buf = readChunked(ConveyorCodec.DB_STAND, count * ConveyorCodec.SIZE_STAND);
        return ConveyorCodec.decodeStandTasks(buf, count);
    }

    public synchronized List<StandTaskStateRow> readStandTaskStates() {
        int count = ensureCapacities().getActionSize();
        byte[] buf = readChunked(ConveyorCodec.DB_STAND_STATE, count * ConveyorCodec.SIZE_STAND_STATE);
        return ConveyorCodec.decodeStandTaskStates(buf, count);
    }

    public synchronized List<RequestStateRow> readRequestStates() {
        int count = ensureCapacities().getRequestSize();
        byte[] buf = readChunked(ConveyorCodec.DB_REQUEST, count * ConveyorCodec.SIZE_REQUEST);
        return ConveyorCodec.decodeRequestStates(buf, count);
    }

    public synchronized List<ShapeStateRow> readShapeStates() {
        int count = ensureCapacities().getShapeSize();
        byte[] buf = readChunked(ConveyorCodec.DB_SHAPE, count * ConveyorCodec.SIZE_SHAPE);
        return ConveyorCodec.decodeShapeStates(buf, count);
    }

    // ===== 写：下发输送任务（轨迹 + 任务，加锁三步）=====
    public synchronized void writeTransTask(long taskNo, List<Integer> tracePoints, int startPoint,
                                            int endPoint, int taskParam, long userData, int updateTimes) {
        // 1) 找空轨迹槽
        List<TransTraceRow> traces = readTransTraces();
        int traceIdx = firstFreeIndex(traces.stream().map(TransTraceRow::getTaskNo).toArray(Long[]::new));
        if (traceIdx < 0) {
            throw new IllegalStateException("无可用的输送轨迹容器");
        }
        int traceOff = traceIdx * ConveyorCodec.SIZE_TRACE;
        s7().writeDataBlock(ConveyorCodec.DB_TRACE, traceOff, ConveyorCodec.LOCK_FLAG);
        s7().writeDataBlock(ConveyorCodec.DB_TRACE, traceOff,
                ConveyorCodec.encodeTransTrace(taskNo, userData, updateTimes, tracePoints));

        // 2) 找空任务槽
        List<TransTaskRow> tasks = readTransTasks();
        int taskIdx = firstFreeIndex(tasks.stream().map(TransTaskRow::getTaskNo).toArray(Long[]::new));
        if (taskIdx < 0) {
            throw new IllegalStateException("无可用的输送任务容器");
        }
        int taskOff = taskIdx * ConveyorCodec.SIZE_TASK;
        s7().writeDataBlock(ConveyorCodec.DB_TASK, taskOff, ConveyorCodec.LOCK_FLAG);
        s7().writeDataBlock(ConveyorCodec.DB_TASK, taskOff,
                ConveyorCodec.encodeTransTask(taskNo, ConveyorCodec.TASK_TYPE_ADD, taskParam,
                        startPoint, endPoint, userData, updateTimes));

        // 3) 解锁
        s7().writeDataBlock(ConveyorCodec.DB_TRACE, traceOff, ConveyorCodec.UNLOCK_FLAG);
        s7().writeDataBlock(ConveyorCodec.DB_TASK, taskOff, ConveyorCodec.UNLOCK_FLAG);
    }

    // ===== 写：删除输送任务 =====
    public synchronized void removeTransTask(long taskNo) {
        if (taskNo == 0) {
            return;
        }
        List<TransTaskRow> tasks = readTransTasks();
        int idx = indexOfTaskNo(tasks.stream().map(TransTaskRow::getTaskNo).toArray(Long[]::new), taskNo);
        if (idx < 0) {
            idx = firstFreeIndex(tasks.stream().map(TransTaskRow::getTaskNo).toArray(Long[]::new));
        }
        if (idx < 0) {
            throw new IllegalStateException("未找到对应的输送任务号，或任务容量不足");
        }
        int off = idx * ConveyorCodec.SIZE_TASK;
        s7().writeDataBlock(ConveyorCodec.DB_TASK, off, ConveyorCodec.LOCK_FLAG);
        s7().writeDataBlock(ConveyorCodec.DB_TASK, off,
                ConveyorCodec.encodeTransTask(taskNo, ConveyorCodec.TASK_TYPE_REMOVE, 0, 0, 0, 0, 99));
        s7().writeDataBlock(ConveyorCodec.DB_TASK, off, ConveyorCodec.UNLOCK_FLAG);
    }

    // ===== 写：清理输送任务（清零任务槽 + 轨迹槽）=====
    public synchronized void clearTransTask(long taskNo) {
        List<TransTaskRow> tasks = readTransTasks();
        int ti = indexOfTaskNo(tasks.stream().map(TransTaskRow::getTaskNo).toArray(Long[]::new), taskNo);
        if (ti >= 0) {
            s7().writeDataBlock(ConveyorCodec.DB_TASK, ti * ConveyorCodec.SIZE_TASK, new byte[ConveyorCodec.SIZE_TASK]);
        }
        List<TransTraceRow> traces = readTransTraces();
        int tri = indexOfTaskNo(traces.stream().map(TransTraceRow::getTaskNo).toArray(Long[]::new), taskNo);
        if (tri >= 0) {
            s7().writeDataBlock(ConveyorCodec.DB_TRACE, tri * ConveyorCodec.SIZE_TRACE, new byte[ConveyorCodec.SIZE_TRACE]);
        }
    }

    // ===== 写：下发单机任务 =====
    public synchronized void writeStandTask(long taskNo, int pointCode, int actionType,
                                            int actionParam1, int actionParam2) {
        List<StandTaskRow> stands = readStandTasks();
        int idx = firstFreeIndex(stands.stream().map(StandTaskRow::getTaskNo).toArray(Long[]::new));
        if (idx < 0) {
            throw new IllegalStateException("无可用的单机任务容器");
        }
        int off = idx * ConveyorCodec.SIZE_STAND;
        s7().writeDataBlock(ConveyorCodec.DB_STAND, off, ConveyorCodec.LOCK_FLAG);
        s7().writeDataBlock(ConveyorCodec.DB_STAND, off,
                ConveyorCodec.encodeStandTask(taskNo, ConveyorCodec.STAND_TASK_ADD, pointCode,
                        actionType, actionParam1, actionParam2));
        s7().writeDataBlock(ConveyorCodec.DB_STAND, off, ConveyorCodec.UNLOCK_FLAG);
    }

    // ===== 写：设置请求信号 =====
    public synchronized void setRequestSignal(int pointCode, int requestState) {
        if (pointCode == 0) {
            return;
        }
        List<RequestStateRow> reqs = readRequestStates();
        int idx = -1;
        for (int i = 0; i < reqs.size(); i++) {
            if (reqs.get(i).getPointCode() == pointCode) { idx = i; break; }
        }
        if (idx < 0) {
            throw new IllegalStateException("未找到对应点位的请求信号");
        }
        int off = idx * ConveyorCodec.SIZE_REQUEST;
        s7().writeDataBlock(ConveyorCodec.DB_REQUEST, off,
                ConveyorCodec.encodeRequestSignal(pointCode, requestState));
    }

    // ===== 工具 =====
    private static int firstFreeIndex(Long[] taskNos) {
        for (int i = 0; i < taskNos.length; i++) {
            if (taskNos[i] != null && taskNos[i] == 0L) return i;
        }
        return -1;
    }

    private static int indexOfTaskNo(Long[] taskNos, long taskNo) {
        for (int i = 0; i < taskNos.length; i++) {
            if (taskNos[i] != null && taskNos[i] == taskNo) return i;
        }
        return -1;
    }
}
