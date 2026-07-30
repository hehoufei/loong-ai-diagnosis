package cn.aimstek.loong.aidiag.qltool.controller;

import cn.aimstek.loong.aidiag.common.BaseResponse;
import cn.aimstek.loong.aidiag.common.Response;
import cn.aimstek.loong.aidiag.qltool.config.QlToolProperties;
import cn.aimstek.loong.aidiag.qltool.config.QlToolProperties.DeviceConfig;
import cn.aimstek.loong.aidiag.qltool.dto.DeviceItem;
import cn.aimstek.loong.aidiag.qltool.dto.DeviceTreeGroup;
import cn.aimstek.loong.aidiag.qltool.dto.SiteItem;
import cn.aimstek.loong.aidiag.qltool.service.DeviceSessionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 青龙调试工具 - 通用接口（设备树、连接、断开、模块设置）。
 *
 * <p>独立路由前缀 /api/v1/ql-tool，与现有接口无重叠；异常本地化处理，不外溢影响其它功能。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ql-tool")
@RequiredArgsConstructor
public class QlToolController {

    private final DeviceSessionManager manager;
    private final QlToolProperties properties;

    /** 设备树（按类型分组） */
    @GetMapping("/devices")
    public Response<List<DeviceTreeGroup>> devices() {
        return BaseResponse.success(manager.getDeviceTree());
    }

    @GetMapping("/sites")
    public Response<List<SiteItem>> sites() {
        return BaseResponse.success(manager.getSites());
    }

    @PostMapping("/sites")
    public Response<SiteItem> addSite(@RequestBody Map<String, String> body) {
        try {
            return BaseResponse.success(manager.addSite(body.get("siteName")));
        } catch (IllegalArgumentException e) {
            return BaseResponse.failure("INVALID_SITE", e.getMessage());
        }
    }

    @PutMapping("/sites/{siteName}")
    public Response<SiteItem> renameSite(@PathVariable String siteName, @RequestBody Map<String, String> body) {
        try {
            return BaseResponse.success(manager.renameSite(siteName, body.get("siteName")));
        } catch (IllegalArgumentException e) {
            return BaseResponse.failure("INVALID_SITE", e.getMessage());
        }
    }

    @DeleteMapping("/sites/{siteName}")
    public Response<Void> removeSite(@PathVariable String siteName) {
        try {
            manager.removeSite(siteName);
            return BaseResponse.success(null);
        } catch (IllegalArgumentException e) {
            return BaseResponse.failure("INVALID_SITE", e.getMessage());
        }
    }

    /** 单设备信息 */
    @GetMapping("/{deviceId}/info")
    public Response<DeviceItem> info(@PathVariable String deviceId) {
        try {
            return BaseResponse.success(manager.getDeviceItem(deviceId));
        } catch (IllegalArgumentException e) {
            return BaseResponse.failure("NOT_FOUND", e.getMessage());
        }
    }

    /** 连接设备 */
    @PostMapping("/{deviceId}/connect")
    public Response<DeviceItem> connect(@PathVariable String deviceId) {
        try {
            manager.connect(deviceId);
            return BaseResponse.success(manager.getDeviceItem(deviceId));
        } catch (IllegalArgumentException e) {
            return BaseResponse.failure("NOT_FOUND", e.getMessage());
        } catch (Exception e) {
            log.warn("[青龙调试工具] 连接设备 {} 失败: {}", deviceId, e.getMessage());
            return BaseResponse.failure("CONNECT_ERROR", e.getMessage());
        }
    }

    /** 断开设备 */
    @PostMapping("/{deviceId}/disconnect")
    public Response<DeviceItem> disconnect(@PathVariable String deviceId) {
        try {
            manager.disconnect(deviceId);
            return BaseResponse.success(manager.getDeviceItem(deviceId));
        } catch (IllegalArgumentException e) {
            return BaseResponse.failure("NOT_FOUND", e.getMessage());
        } catch (Exception e) {
            return BaseResponse.failure("DISCONNECT_ERROR", e.getMessage());
        }
    }

    /** 模块前端设置（轮询间隔等） */
    @GetMapping("/settings")
    public Response<Map<String, Object>> settings() {
        return BaseResponse.success(Map.of(
                "pollIntervalMs", properties.getPollIntervalMs(),
                "connectTimeoutMs", properties.getConnectTimeoutMs(),
                "readTimeoutMs", properties.getReadTimeoutMs()
        ));
    }

    // ==================== 设备管理（运行时增删改） ====================

    /** 添加设备 */
    @PostMapping("/devices")
    public Response<DeviceItem> addDevice(@RequestBody DeviceConfig config) {
        try {
            if (config.getIp() == null || config.getIp().isBlank()) {
                return BaseResponse.failure("INVALID_PARAM", "IP地址不能为空");
            }
            if (config.getDeviceType() == null || config.getDeviceType().isBlank()) {
                return BaseResponse.failure("INVALID_PARAM", "设备类型不能为空");
            }
            if (config.getDeviceName() == null || config.getDeviceName().isBlank()) {
                return BaseResponse.failure("INVALID_PARAM", "设备名称不能为空");
            }
            DeviceItem item = manager.addDevice(config);
            return BaseResponse.success(item);
        } catch (IllegalArgumentException e) {
            return BaseResponse.failure("DUPLICATE", e.getMessage());
        } catch (Exception e) {
            log.warn("[青龙调试工具] 添加设备失败: {}", e.getMessage());
            return BaseResponse.failure("ADD_ERROR", e.getMessage());
        }
    }

    /** 保存同一现场、同一设备类型下的手动排序。 */
    @PutMapping("/devices/order")
    public Response<Void> reorderDevices(@RequestBody DeviceOrderRequest request) {
        try {
            manager.reorderDevices(request.siteName(), request.deviceType(), request.deviceIds());
            return BaseResponse.success(null);
        } catch (IllegalArgumentException e) {
            return BaseResponse.failure("INVALID_ORDER", e.getMessage());
        } catch (Exception e) {
            log.warn("[青龙调试工具] 保存设备排序失败: {}", e.getMessage());
            return BaseResponse.failure("ORDER_ERROR", e.getMessage());
        }
    }

    /** 更新设备配置 */
    @PutMapping("/devices/{deviceId}")
    public Response<DeviceItem> updateDevice(@PathVariable String deviceId, @RequestBody DeviceConfig config) {
        try {
            DeviceItem item = manager.updateDevice(deviceId, config);
            return BaseResponse.success(item);
        } catch (IllegalArgumentException e) {
            return BaseResponse.failure("NOT_FOUND", e.getMessage());
        } catch (Exception e) {
            log.warn("[青龙调试工具] 更新设备 {} 失败: {}", deviceId, e.getMessage());
            return BaseResponse.failure("UPDATE_ERROR", e.getMessage());
        }
    }

    /** 删除设备 */
    @DeleteMapping("/devices/{deviceId}")
    public Response<Void> removeDevice(@PathVariable String deviceId) {
        try {
            manager.removeDevice(deviceId);
            return BaseResponse.success(null);
        } catch (IllegalArgumentException e) {
            return BaseResponse.failure("NOT_FOUND", e.getMessage());
        } catch (Exception e) {
            log.warn("[青龙调试工具] 删除设备 {} 失败: {}", deviceId, e.getMessage());
            return BaseResponse.failure("DELETE_ERROR", e.getMessage());
        }
    }

    public record DeviceOrderRequest(String siteName, String deviceType, List<String> deviceIds) {}
}
