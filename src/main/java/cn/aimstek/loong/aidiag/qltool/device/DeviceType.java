package cn.aimstek.loong.aidiag.qltool.device;

/**
 * 青龙调试工具支持的设备类型（本期：堆垛机、输送线；其余预留）。
 */
public enum DeviceType {
    /** 堆垛机 */
    STACKER_CRANE("堆垛机"),
    /** 输送线 */
    CONVEYOR_LINE("输送线"),
    /** 未知/未支持 */
    UNKNOWN("未知");

    private final String label;

    DeviceType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public static DeviceType from(String value) {
        if (value == null) {
            return UNKNOWN;
        }
        try {
            return DeviceType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }
}
