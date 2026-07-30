package cn.aimstek.loong.aidiag.qltool.device.crane;

import cn.aimstek.loong.aidiag.qltool.codec.S7Reader;
import cn.aimstek.loong.aidiag.qltool.codec.S7Writer;
import cn.aimstek.loong.aidiag.qltool.dto.crane.CraneStatusDto;
import cn.aimstek.loong.aidiag.qltool.dto.crane.CraneStatusDto.ForkStatusDto;
import cn.aimstek.loong.aidiag.qltool.dto.crane.CraneStatusDto.TaskResultDto;
import cn.aimstek.loong.aidiag.qltool.dto.crane.CraneTaskRequests.*;

/**
 * 堆垛机二进制编解码（对应 LoongAssist.Device.Crane.Metadata + MetadataExtension）。
 *
 * <p>PLC 大端序：状态字段直接大端读；写入任务时 .NET"先 SwapEndianness 再小端落盘"的净效果即大端，
 * Java 直接大端写。两处特例（Fork2CargoType=0x1000 原始未 swap、ClearTask.TaskNo 未 swap）用小端写复刻。
 */
public final class CraneCodec {

    private CraneCodec() {}

    // ===== DB 地址（对应 .NET 常量）=====
    public static final int STATE_DB = 531;
    public static final int STATE_OFFSET = 0;
    public static final int STATE_SIZE = 68;

    public static final int RESULT_DB = 531;
    public static final int RESULT_OFFSET = 66;
    public static final int RESULT_SIZE = 10;

    public static final int TASK_DB = 530;
    public static final int TASK_OFFSET = 12;

    // ===== 解析状态 =====
    public static CraneStatusDto decodeState(byte[] data) {
        S7Reader r = new S7Reader(data);
        CraneStatusDto s = new CraneStatusDto();
        int workMode = r.u1();
        int taskStatus = r.u1();
        s.setWorkMode(workMode);
        s.setWorkModeLabel(CraneEnums.workMode(workMode));
        s.setTaskStatus(taskStatus);
        s.setTaskStatusLabel(CraneEnums.taskState(taskStatus));
        s.setTaskNo(r.u4());
        s.setRowStation(r.u2());
        int dockState = r.u2();
        s.setDockState(dockState);
        s.setDockStateLabel(CraneEnums.dockState(dockState));
        byte[] alarmCode = r.bytes(24);
        java.util.List<Integer> alarmCodes = parseAlarmCodes(alarmCode);
        java.util.List<String> alarmList = new java.util.ArrayList<>(alarmCodes.size());
        for (Integer code : alarmCodes) {
            alarmList.add(CraneAlarmCode.display(code));
        }
        s.setAlarmCodes(alarmCodes);
        s.setAlarmList(alarmList);
        s.setAlarmMessage(String.join(" | ", alarmList));
        s.setDockHorizontalPulse(r.u4());
        s.setDockVerticalPulse(r.u4());

        s.setFork1(readFork(r));
        s.setFork2(readFork(r));
        return s;
    }

    private static ForkStatusDto readFork(S7Reader r) {
        ForkStatusDto f = new ForkStatusDto();
        f.setPulse(r.u4());
        f.setColStation(r.u2());
        int colValid = r.u2();
        f.setColValid(colValid);
        f.setColValidLabel(CraneEnums.valueValid(colValid));
        int colBack = r.u2();
        f.setColBack(colBack);
        f.setColBackLabel(CraneEnums.forkState(colBack));
        int hasLoad = r.u1();
        f.setHasLoad(hasLoad);
        f.setHasLoadLabel(CraneEnums.hasLoad(hasLoad));
        int active = r.u1();
        f.setActive(active);
        f.setActiveLabel(CraneEnums.forkAction(active));
        return f;
    }

    // ===== 解析指令反馈 =====
    public static TaskResultDto decodeResult(byte[] data) {
        S7Reader r = new S7Reader(data);
        TaskResultDto t = new TaskResultDto();
        t.setCommandType(r.ascii(2));
        t.setResultType(r.ascii(2));
        t.setTaskNo(r.u4());
        t.setResultCode(r.u2());
        return t;
    }

    /**
     * 报警位号解析：按字节内低位优先扫描，返回全部置位的位号。
     *
     * <p>位序与 iot {@code AbstractDeviceDriver#buildAlarmMsgV2} 一致：位号 = 字节下标 * 8 + 字节内位号。
     */
    private static java.util.List<Integer> parseAlarmCodes(byte[] alarmCodes) {
        java.util.List<Integer> codes = new java.util.ArrayList<>();
        for (int i = 0; i < alarmCodes.length; i++) {
            int b = alarmCodes[i] & 0xFF;
            if (b == 0) {
                continue;
            }
            for (int bit = 0; bit < 8; bit++) {
                if ((b & (1 << bit)) != 0) {
                    codes.add(i * 8 + bit);
                }
            }
        }
        return codes;
    }

    // ===== 货载类型（对应 GetCargoType）=====
    private static int cargoType(int width, int height, int depth) {
        int base = 0x1000;
        int widthFlag = width == 0 ? 0x0001 : (width & 0x0f);
        int heightFlag = height == 0 ? 0x0010 : ((height & 0x0f) << 4);
        int depthFlag = depth == 0 ? 0x0100 : ((depth & 0x0f) << 8);
        return (base + widthFlag + heightFlag + depthFlag) & 0xFFFF;
    }

    // ===== 编码任务（DB530 offset12）=====
    /** 搬运任务 'CA'，26 字节 */
    public static byte[] encodeCarry(CarryTaskRequest t) {
        return new S7Writer(26)
                .ascii("CA", 2)
                .u4(t.getTaskNo())
                .u2(t.getPickupCol())
                .u2(t.getPickupRow())
                .u2(t.getDropoffCol())
                .u2(t.getDropoffRow())
                .u2(t.getPickupLine())      // Fork1PickupLine
                .u2(t.getDropoffLine())     // Fork1DropoffLine
                .u2(0)                       // Fork2PickupLine
                .u2(0)                       // Fork2DropoffLine
                .u2(cargoType(t.getCargoWidth(), t.getCargoHeight(), t.getCargoDepth())) // Fork1CargoType
                .u2Le(0x1000)                // Fork2CargoType（.NET 原始值小端落盘）
                .toBytes();
    }

    /** 行走(移动)任务 'CD'，18 字节 */
    public static byte[] encodeMove(MoveTaskRequest t) {
        return new S7Writer(18)
                .ascii("CD", 2)
                .u4(t.getTaskNo())
                .u2(t.getTargetCol())
                .u2(t.getTargetRow())
                .u2(t.getTargetLine())      // Fork1TargetLine
                .u2(0)                       // Fork2TargetLine
                .u2(cargoType(t.getCargoWidth(), t.getCargoHeight(), t.getCargoDepth()))
                .u2Le(0x1000)
                .toBytes();
    }

    /** 取货任务 'CB'，18 字节 */
    public static byte[] encodePickup(PickupTaskRequest t) {
        return new S7Writer(18)
                .ascii("CB", 2)
                .u4(t.getTaskNo())
                .u2(t.getPickupCol())
                .u2(t.getPickupRow())
                .u2(t.getPickupLine())      // Fork1PickupLine
                .u2(0)                       // Fork2PickupLine
                .u2(cargoType(t.getCargoWidth(), t.getCargoHeight(), t.getCargoDepth()))
                .u2Le(0x1000)
                .toBytes();
    }

    /** 放货任务 'CC'，18 字节 */
    public static byte[] encodeDropoff(DropoffTaskRequest t) {
        return new S7Writer(18)
                .ascii("CC", 2)
                .u4(t.getTaskNo())
                .u2(t.getDropoffCol())
                .u2(t.getDropoffRow())
                .u2(t.getDropoffLine())     // Fork1DropoffLine
                .u2(0)                       // Fork2DropoffLine
                .u2(cargoType(t.getCargoWidth(), t.getCargoHeight(), t.getCargoDepth()))
                .u2Le(0x1000)
                .toBytes();
    }

    /** 清除任务 'CF'，30 字节（TaskNo 未 swap → 小端；Reserved[24]）*/
    public static byte[] encodeClear(long taskNo) {
        return new S7Writer(30)
                .ascii("CF", 2)
                .u4Le(taskNo)
                .zeros(24)
                .toBytes();
    }
}
