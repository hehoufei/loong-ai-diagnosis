package cn.aimstek.loong.aidiag.qltool.device.crane;

import cn.aimstek.loong.aidiag.qltool.dto.crane.CraneStatusDto;
import cn.aimstek.loong.aidiag.qltool.dto.crane.CraneTaskRequests.*;
import cn.aimstek.loong.aidiag.qltool.service.DeviceSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 堆垛机连接器（对应 LoongAssist.Device.Crane.CraneConnector）。
 *
 * <p>基于 {@link DeviceSession} 的 S7Client；所有读写在 session 上串行（单连接不并发）。
 */
public class CraneConnector {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final DeviceSession session;

    public CraneConnector(DeviceSession session) {
        this.session = session;
    }

    public boolean uses(DeviceSession candidate) {
        return this.session == candidate;
    }

    /**
     * 读取完整状态（车体+货叉+指令反馈）。
     * 状态(0..68)与指令反馈(66..76)同在 DB531，一次读取 76 字节即可覆盖，
     * 避免两次 S7 往返（性能优化）。
     */
    public synchronized CraneStatusDto readStatus() {
        int total = CraneCodec.RESULT_OFFSET + CraneCodec.RESULT_SIZE; // 76
        byte[] buf = session.getS7().readDataBlock(CraneCodec.STATE_DB, CraneCodec.STATE_OFFSET, total);

        CraneStatusDto dto = CraneCodec.decodeState(buf);
        byte[] resultBuf = java.util.Arrays.copyOfRange(buf, CraneCodec.RESULT_OFFSET, total);
        dto.setResult(CraneCodec.decodeResult(resultBuf));

        dto.setRefreshTime(LocalDateTime.now().format(TS));
        return dto;
    }

    public synchronized void writeCarryTask(CarryTaskRequest t) {
        session.getS7().writeDataBlock(CraneCodec.TASK_DB, CraneCodec.TASK_OFFSET, CraneCodec.encodeCarry(t));
    }

    public synchronized void writeMoveTask(MoveTaskRequest t) {
        session.getS7().writeDataBlock(CraneCodec.TASK_DB, CraneCodec.TASK_OFFSET, CraneCodec.encodeMove(t));
    }

    public synchronized void writePickupTask(PickupTaskRequest t) {
        session.getS7().writeDataBlock(CraneCodec.TASK_DB, CraneCodec.TASK_OFFSET, CraneCodec.encodePickup(t));
    }

    public synchronized void writeDropoffTask(DropoffTaskRequest t) {
        session.getS7().writeDataBlock(CraneCodec.TASK_DB, CraneCodec.TASK_OFFSET, CraneCodec.encodeDropoff(t));
    }

    public synchronized void writeClearTask(long taskNo) {
        session.getS7().writeDataBlock(CraneCodec.TASK_DB, CraneCodec.TASK_OFFSET, CraneCodec.encodeClear(taskNo));
    }
}
