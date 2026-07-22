package cn.aimstek.loong.aidiag.qltool.controller;

import cn.aimstek.loong.aidiag.common.BaseResponse;
import cn.aimstek.loong.aidiag.common.Response;
import cn.aimstek.loong.aidiag.qltool.device.crane.CraneConnector;
import cn.aimstek.loong.aidiag.qltool.dto.crane.CraneStatusDto;
import cn.aimstek.loong.aidiag.qltool.dto.crane.CraneTaskRequests.*;
import cn.aimstek.loong.aidiag.qltool.service.DeviceSessionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 堆垛机调试接口 /api/v1/ql-tool/crane/**（独立于现有功能）。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ql-tool/crane")
@RequiredArgsConstructor
public class CraneDebugController {

    private final DeviceSessionManager manager;

    /** 每设备复用连接器实例 */
    private final java.util.concurrent.ConcurrentHashMap<String, CraneConnector> connectors =
            new java.util.concurrent.ConcurrentHashMap<>();

    private CraneConnector connector(String deviceId) {
        return connectors.compute(deviceId, (id, existing) -> {
            var currentSession = manager.session(id);
            return existing != null && existing.uses(currentSession)
                    ? existing
                    : new CraneConnector(currentSession);
        });
    }

    @GetMapping("/{deviceId}/status")
    public Response<CraneStatusDto> status(@PathVariable String deviceId) {
        try {
            return BaseResponse.success(connector(deviceId).readStatus());
        } catch (IllegalArgumentException e) {
            return BaseResponse.failure("NOT_FOUND", e.getMessage());
        } catch (Exception e) {
            return BaseResponse.failure("READ_ERROR", e.getMessage());
        }
    }

    @PostMapping("/{deviceId}/carry-task")
    public Response<Void> carry(@PathVariable String deviceId, @RequestBody CarryTaskRequest req) {
        return doWrite(deviceId, c -> c.writeCarryTask(req));
    }

    @PostMapping("/{deviceId}/move-task")
    public Response<Void> move(@PathVariable String deviceId, @RequestBody MoveTaskRequest req) {
        return doWrite(deviceId, c -> c.writeMoveTask(req));
    }

    @PostMapping("/{deviceId}/pickup-task")
    public Response<Void> pickup(@PathVariable String deviceId, @RequestBody PickupTaskRequest req) {
        return doWrite(deviceId, c -> c.writePickupTask(req));
    }

    @PostMapping("/{deviceId}/dropoff-task")
    public Response<Void> dropoff(@PathVariable String deviceId, @RequestBody DropoffTaskRequest req) {
        return doWrite(deviceId, c -> c.writeDropoffTask(req));
    }

    @PostMapping("/{deviceId}/clear-task")
    public Response<Void> clear(@PathVariable String deviceId, @RequestBody ClearTaskRequest req) {
        return doWrite(deviceId, c -> c.writeClearTask(req.getTaskNo()));
    }

    // ===== 内部 =====
    private interface CraneAction { void run(CraneConnector c); }

    private Response<Void> doWrite(String deviceId, CraneAction action) {
        try {
            action.run(connector(deviceId));
            return BaseResponse.success(null);
        } catch (IllegalArgumentException e) {
            return BaseResponse.failure("NOT_FOUND", e.getMessage());
        } catch (Exception e) {
            log.warn("[青龙调试工具] 堆垛机 {} 下发失败: {}", deviceId, e.getMessage());
            return BaseResponse.failure("WRITE_ERROR", e.getMessage());
        }
    }
}
