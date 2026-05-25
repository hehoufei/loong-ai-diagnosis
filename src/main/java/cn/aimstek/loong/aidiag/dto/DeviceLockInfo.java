package cn.aimstek.loong.aidiag.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 设备锁定状态 DTO，对应 loong-platform 表 sc_device_lock。
 */
@Data
public class DeviceLockInfo {

    /** 主键ID */
    private Long id;

    /** 设备编码 */
    private String deviceCode;

    /** 当前执行的任务号 */
    private String taskNo;

    /** 当前执行的子任务号 */
    private String taskItemNo;

    /** 当前执行的指令号 */
    private String commandNo;

    /** 设备锁状态 */
    private String lockState;

    /** 锁定时间（取自 update_time/create_time） */
    private LocalDateTime lockTime;
}
