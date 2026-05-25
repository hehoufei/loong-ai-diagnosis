package cn.aimstek.loong.aidiag.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据采集状态 DTO，对应 loong-platform 表 sc_device_collected_data。
 */
@Data
public class CollectedDataInfo {

    /** 主键ID */
    private Long id;

    /** 设备编码 */
    private String deviceCode;

    /** 任务号 */
    private String taskNo;

    /** 子任务号 */
    private String taskItemNo;

    /** 指令号 */
    private String commandNo;

    /** 点位code */
    private String nodeCode;

    /** 采集设备类型 */
    private String deviceType;

    /** 数据处理类型 */
    private String dataProcessType;

    /** 采集状态：COLLECTING/SUCCESS/FAILED/CANCELED */
    private String state;

    /** 确认状态 */
    private String ackState;

    /** 上报内容 */
    private String content;

    /** 采集顺序 */
    private Integer collectedIndex;

    /** 需要采集的数据数量 */
    private Integer requiredCount;

    /** 创建时间 */
    private LocalDateTime createTime;
}
