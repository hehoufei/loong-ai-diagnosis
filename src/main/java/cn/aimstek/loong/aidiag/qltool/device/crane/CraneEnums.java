package cn.aimstek.loong.aidiag.qltool.device.crane;

/**
 * 堆垛机枚举中文标签（对应 LoongAssist.Device.Crane.Enums 的 [Description]）。
 */
public final class CraneEnums {

    private CraneEnums() {}

    public static String workMode(int v) {
        switch (v) {
            case 1: return "初始化";
            case 2: return "待命中";
            case 3: return "运行";
            case 4: return "报警停机";
            case 5: return "停机警告";
            case 6: return "手动";
            case 7: return "上位机急停";
            case 8: return "半自动";
            default: return "未知";
        }
    }

    public static String taskState(int v) {
        switch (v) {
            case 1: return "初始化";
            case 2: return "移动";
            case 3: return "货叉动作";
            case 4: return "任务手动完成";
            case 5: return "任务自动完成";
            case 6: return "任务手动删除";
            case 7: return "任务自动删除";
            default: return "未知";
        }
    }

    public static String dockState(int v) {
        switch (v) {
            case 0: return "未初始化";
            case 2: return "低位";
            case 3: return "高位";
            default: return "未知";
        }
    }

    public static String forkAction(int v) {
        switch (v) {
            case 1: return "待命";
            case 2: return "取货中";
            case 3: return "放货中";
            default: return "未知";
        }
    }

    public static String forkState(int v) {
        switch (v) {
            case 1: return "中位";
            case 2: return "左一排";
            case 3: return "左一排到位";
            case 4: return "左二排";
            case 5: return "左二排到位";
            case 6: return "右一排";
            case 7: return "右一排到位";
            case 8: return "右二排";
            case 9: return "右二排到位";
            default: return "未知";
        }
    }

    public static String hasLoad(int v) {
        switch (v) {
            case 1: return "无货";
            case 2: return "有货";
            default: return "未知";
        }
    }

    public static String valueValid(int v) {
        switch (v) {
            case 1: return "无效";
            case 2: return "有效";
            default: return "未知";
        }
    }
}
