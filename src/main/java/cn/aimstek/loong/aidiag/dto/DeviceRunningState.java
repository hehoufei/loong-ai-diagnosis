package cn.aimstek.loong.aidiag.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 设备运行状态 DTO（对应 loong-platform DeviceRunningStateDTO）。
 * 通过 POST /dcs/queryDeviceByDeviceCode?deviceCode=xxx 查询。
 */
@Data
public class DeviceRunningState {

    /** 设备编码 */
    private String deviceCode;

    /** 设备类型 */
    private String deviceType;

    /** 父设备编码 */
    private String parentDeviceCode;

    /** 是否在线 */
    private Boolean onlineState;

    /** 上报时间 */
    private String reportTime;

    /** 工作模式：AUTO/MANUAL/MAINTAIN 等 */
    private String workMode;

    /** 设备状态：IDLE/RUNNING/FAULT/STOPPED/OFFLINE */
    private String deviceState;

    /** DCS 任务状态：IDLE/BUSY 等 */
    private String dcsTaskState;

    /** 任务占位状态：HAS_TASK/NO_TASK */
    private String hasTask;

    /** 货物占位状态：HAS_GOODS/EMPTY/FULL_GOODS */
    private String hasGoods;

    /** 电气任务号 */
    private String plcTaskNo;

    /** 报警列表 */
    private List<AlarmInfo> alarmInfoList = new ArrayList<>();

    /**
     * 报警信息内部类（对应 AlarmInfoDTO）。
     */
    @Data
    public static class AlarmInfo {
        /** 报警编码 */
        private String alarmCode;
        /** 报警内容 */
        private String alarmMessage;
        /** 报警级别 */
        private String alarmLevel;
        /** 报警类型 */
        private String alarmType;
        /** 首次报警时间 */
        private String firstAlarmTime;
        /** 时间戳 */
        private String timestamp;
    }
}
