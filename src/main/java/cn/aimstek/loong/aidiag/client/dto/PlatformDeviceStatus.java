package cn.aimstek.loong.aidiag.client.dto;

import lombok.Data;

/**
 * 设备状态（可选字段）。
 */
@Data
public class PlatformDeviceStatus {

    private String deviceCode;
    private String deviceType;
    private String deviceStatus;
    private String onlineStatus;
}
