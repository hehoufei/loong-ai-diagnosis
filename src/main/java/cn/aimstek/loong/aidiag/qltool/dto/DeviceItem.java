package cn.aimstek.loong.aidiag.qltool.dto;

import lombok.Data;

/**
 * 设备条目（设备树叶子 / 面板头部展示）。
 */
@Data
public class DeviceItem {
    private String deviceId;
    private String deviceName;
    private String siteName;
    private String deviceType;
    private String deviceTypeLabel;
    private String ip;
    private int port;
    private int rack;
    private int slot;
    private String protocol;
    private String connectionType;
    private boolean connected;
}
