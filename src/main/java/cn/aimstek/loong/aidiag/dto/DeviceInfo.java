package cn.aimstek.loong.aidiag.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 设备实时状态信息 DTO，对应 loong-platform IoT 设备缓存查询接口返回。
 */
@Data
public class DeviceInfo {
    /** 设备编码 */
    private String deviceCode;
    /** 设备类型编码 */
    private String deviceTypeCode;
    /** 设备类型名称（如"拆叠盘机"） */
    private String deviceTypeName;
    /** 最近数据采集时间 */
    private String collectTime;
    /** 是否在线 */
    private Boolean online;
    /** 最近更新时间map */
    private Map<String, String> lastUpdateMap;
    /** 设备状态信息 */
    private StateInfo stateInfo;

    @Data
    public static class StateInfo {
        /** 点位编码 */
        private Integer pointCode;
        /** 工作模式 (1=自动, 0=手动 等) */
        private Integer workMode;
        /** 工作状态 (1=空闲, 2=运行 等) */
        private Integer workState;
        /** 当前任务号 */
        private Integer taskNo;
        /** 任务状态 */
        private Integer taskState;
        /** 负载状态 (1=有货, 0=无货 等) */
        private Integer loadState;
        /** 任务错误码 */
        private Integer taskError;
        /** 报警信息列表 */
        private List<Object> alarmInfoList;
    }
}
