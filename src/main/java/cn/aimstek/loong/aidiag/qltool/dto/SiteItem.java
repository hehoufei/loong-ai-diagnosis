package cn.aimstek.loong.aidiag.qltool.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 调试现场及其设备数量。
 */
@Data
@AllArgsConstructor
public class SiteItem {
    private String siteName;
    private int deviceCount;
    private int stackerCount;
    private int conveyorCount;
}
