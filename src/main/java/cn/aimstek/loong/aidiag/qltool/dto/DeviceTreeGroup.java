package cn.aimstek.loong.aidiag.qltool.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 设备树分组（按设备类型分组，对应左侧导航）。
 */
@Data
public class DeviceTreeGroup {
    private String groupType;
    private String groupName;
    private List<DeviceItem> devices = new ArrayList<>();
}
