package cn.aimstek.loong.aidiag.qltool.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 青龙调试工具模块配置（独立前缀 ql-tool，不与现有配置耦合）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "ql-tool")
public class QlToolProperties {

    /** 前端状态轮询间隔（毫秒） */
    private int pollIntervalMs = 1000;
    /** 连接超时（毫秒） */
    private int connectTimeoutMs = 3000;
    /** 读写超时（毫秒） */
    private int readTimeoutMs = 2000;
    /** 设备清单 */
    private List<DeviceConfig> devices = new ArrayList<>();

    /**
     * 单个设备配置。
     */
    @Data
    public static class DeviceConfig {
        /** 设备唯一标识 */
        private String deviceId;
        /** 设备显示名 */
        private String deviceName;
        /** 所属现场，用于按项目/客户隔离设备列表 */
        private String siteName = "恒申美达";
        /** 设备类型：STACKER_CRANE / CONVEYOR_LINE */
        private String deviceType;
        /** 通信 IP */
        private String ip;
        /** 端口，S7 默认 102 */
        private int port = 102;
        /** PLC 机架号 */
        private int rack = 0;
        /** PLC 槽号 */
        private int slot = 0;
        /** 协议类型（展示用），如 AIMS_V3 */
        private String protocol = "AIMS_V3";
        /** 通信类型（展示用） */
        private String connectionType = "SiemensS7";
        /** PLC4X 控制器类型：S7_1500 / S7_1200 / S7_300 / S7_400 / LOGO（务必指定，避免握手探测超时） */
        private String controllerType = "S7_1500";
    }
}
