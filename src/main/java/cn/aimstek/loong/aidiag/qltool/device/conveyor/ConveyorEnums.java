package cn.aimstek.loong.aidiag.qltool.device.conveyor;

/**
 * 输送线枚举中文标签（对应 LoongAssist.Device.Conveyor.Enums 的 [Description]）。
 */
public final class ConveyorEnums {

    private ConveyorEnums() {}

    /** 任务类型 TaskTypeEnum */
    public static String taskType(int v) {
        switch (v) {
            case 1: return "新增";
            case 2: return "变更";
            case 3: return "删除任务";
            default: return "未知";
        }
    }

    /** 任务状态 TaskStateEnum */
    public static String taskState(int v) {
        switch (v) {
            case 1: return "等待执行";
            case 2: return "执行中";
            case 3: return "已完成";
            case 4: return "发生错误";
            default: return "未知";
        }
    }

    /** 上位机控制锁 SysLockerEnum */
    public static String sysLocker(int v) {
        switch (v) {
            case 1: return "PLC可读";
            case 2: return "上位可写";
            default: return "未知";
        }
    }

    /** 货位状态 PointStateEnum */
    public static String pointState(int v) {
        switch (v) {
            case 1: return "报警";
            case 2: return "离线";
            case 3: return "手动";
            case 4: return "停止";
            case 5: return "运行";
            default: return "未知";
        }
    }

    /** 占位状态 OccupancyStateEnum */
    public static String occupancyState(int v) {
        switch (v) {
            case 1: return "有占位";
            case 2: return "无占位";
            default: return "未知";
        }
    }

    /** 光电/遮挡 BlockingStateEnum */
    public static String blockingState(int v) {
        switch (v) {
            case 1: return "有遮挡";
            case 2: return "无遮挡";
            default: return "未知";
        }
    }

    /** 单机任务类型 SingleMachineTaskTypeEnum */
    public static String standTaskType(int v) {
        switch (v) {
            case 1: return "添加任务";
            case 2: return "删除任务";
            default: return "未知";
        }
    }

    /** 单机设备(动作)类型 ActionTypeEnum */
    public static String actionType(int v) {
        switch (v) {
            case 1: return "套袋";
            case 2: return "缠膜";
            case 3: return "打带";
            case 4: return "覆膜";
            case 5: return "翻转分离";
            case 6: return "辅助投框";
            case 7: return "拆叠盘机";
            default: return "无";
        }
    }

    /** 点位报警 PointAlarmEnum（[Flags]，取首个命中的标签）*/
    public static String pointAlarm(int v) {
        if (v == 0) return "无";
        if ((v & 0x0100) != 0) return "手动";
        if ((v & 0x0200) != 0) return "离线";
        if ((v & 0x0400) != 0) return "断路器断开";
        if ((v & 0x0800) != 0) return "光电报警";
        if ((v & 0x1000) != 0) return "运行超时";
        if ((v & 0x2000) != 0) return "占位超时";
        if ((v & 0x4000) != 0) return "有任务无货报警";
        if ((v & 0x8000) != 0) return "x轴变频器报警";
        if ((v & 0x0001) != 0) return "Y轴变频器报警";
        if ((v & 0x0002) != 0) return "x轴电机接触器报警";
        if ((v & 0x0004) != 0) return "x轴电机抱闸接触器报警";
        if ((v & 0x0008) != 0) return "Y轴电机接触器报警";
        if ((v & 0x0010) != 0) return "Y轴电机接触器抱闸报警";
        if ((v & 0x0020) != 0) return "顶升电机接触器报警";
        if ((v & 0x0040) != 0) return "顶升电机抱闸接触器报警";
        if ((v & 0x0080) != 0) return "急停";
        return "报警(0x" + Integer.toHexString(v) + ")";
    }
}
