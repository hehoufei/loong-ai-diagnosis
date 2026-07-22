package cn.aimstek.loong.aidiag.qltool.controller;

import cn.aimstek.loong.aidiag.common.BaseResponse;
import cn.aimstek.loong.aidiag.common.Response;
import cn.aimstek.loong.aidiag.qltool.device.conveyor.ConveyorConnector;
import cn.aimstek.loong.aidiag.qltool.dto.conveyor.ConveyorDtos.Capacities;
import cn.aimstek.loong.aidiag.qltool.dto.conveyor.ConveyorTaskRequests.*;
import cn.aimstek.loong.aidiag.qltool.service.DeviceSessionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.function.Function;

/**
 * 输送线调试接口 /api/v1/ql-tool/conveyor/**（独立于现有功能）。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ql-tool/conveyor")
@RequiredArgsConstructor
public class ConveyorDebugController {

    private final DeviceSessionManager manager;

    /** 每设备复用连接器实例，使能力(容量)缓存生效，减少 PLC 读取 */
    private final java.util.concurrent.ConcurrentHashMap<String, ConveyorConnector> connectors =
            new java.util.concurrent.ConcurrentHashMap<>();

    private ConveyorConnector connector(String deviceId) {
        return connectors.compute(deviceId, (id, existing) -> {
            var currentSession = manager.session(id);
            return existing != null && existing.uses(currentSession)
                    ? existing
                    : new ConveyorConnector(currentSession);
        });
    }

    // ===== 读 =====
    @GetMapping("/{deviceId}/capacities")
    public Response<Capacities> capacities(@PathVariable String deviceId) {
        return read(deviceId, ConveyorConnector::capacities);
    }

    @GetMapping("/{deviceId}/node-states")
    public Response<?> nodeStates(@PathVariable String deviceId) {
        return read(deviceId, ConveyorConnector::readNodeStates);
    }

    @GetMapping("/{deviceId}/trans-tasks")
    public Response<?> transTasks(@PathVariable String deviceId) {
        return read(deviceId, ConveyorConnector::readTransTasks);
    }

    @GetMapping("/{deviceId}/trans-traces")
    public Response<?> transTraces(@PathVariable String deviceId) {
        return read(deviceId, ConveyorConnector::readTransTraces);
    }

    @GetMapping("/{deviceId}/trans-task-states")
    public Response<?> transTaskStates(@PathVariable String deviceId) {
        return read(deviceId, ConveyorConnector::readTransTaskStates);
    }

    @GetMapping("/{deviceId}/stand-tasks")
    public Response<?> standTasks(@PathVariable String deviceId) {
        return read(deviceId, ConveyorConnector::readStandTasks);
    }

    @GetMapping("/{deviceId}/stand-task-states")
    public Response<?> standTaskStates(@PathVariable String deviceId) {
        return read(deviceId, ConveyorConnector::readStandTaskStates);
    }

    @GetMapping("/{deviceId}/request-states")
    public Response<?> requestStates(@PathVariable String deviceId) {
        return read(deviceId, ConveyorConnector::readRequestStates);
    }

    @GetMapping("/{deviceId}/shape-states")
    public Response<?> shapeStates(@PathVariable String deviceId) {
        return read(deviceId, ConveyorConnector::readShapeStates);
    }

    // ===== 写 =====
    @PostMapping("/{deviceId}/trans-task")
    public Response<Void> writeTransTask(@PathVariable String deviceId, @RequestBody TransTaskRequest req) {
        return write(deviceId, c -> c.writeTransTask(req.getTaskNo(), req.getTracePoints(), req.getStartPoint(),
                req.getEndPoint(), req.getTaskParam(), req.getUserData(), req.getUpdateTimes()));
    }

    @DeleteMapping("/{deviceId}/trans-task/{taskNo}")
    public Response<Void> removeTransTask(@PathVariable String deviceId, @PathVariable long taskNo) {
        return write(deviceId, c -> c.removeTransTask(taskNo));
    }

    @DeleteMapping("/{deviceId}/trans-task/{taskNo}/clear")
    public Response<Void> clearTransTask(@PathVariable String deviceId, @PathVariable long taskNo) {
        return write(deviceId, c -> c.clearTransTask(taskNo));
    }

    @PostMapping("/{deviceId}/stand-task")
    public Response<Void> writeStandTask(@PathVariable String deviceId, @RequestBody StandTaskRequest req) {
        return write(deviceId, c -> c.writeStandTask(req.getTaskNo(), req.getPointCode(),
                req.getActionType(), req.getActionParam1(), req.getActionParam2()));
    }

    @PostMapping("/{deviceId}/request-signal")
    public Response<Void> setRequestSignal(@PathVariable String deviceId, @RequestBody RequestSignalRequest req) {
        return write(deviceId, c -> c.setRequestSignal(req.getPointCode(), req.getRequestState()));
    }

    // ===== 内部 =====
    private <T> Response<T> read(String deviceId, Function<ConveyorConnector, T> fn) {
        try {
            return BaseResponse.success(fn.apply(connector(deviceId)));
        } catch (IllegalArgumentException e) {
            return BaseResponse.failure("NOT_FOUND", e.getMessage());
        } catch (Exception e) {
            return BaseResponse.failure("READ_ERROR", e.getMessage());
        }
    }

    private interface ConveyorAction { void run(ConveyorConnector c); }

    private Response<Void> write(String deviceId, ConveyorAction action) {
        try {
            action.run(connector(deviceId));
            return BaseResponse.success(null);
        } catch (IllegalArgumentException e) {
            return BaseResponse.failure("NOT_FOUND", e.getMessage());
        } catch (IllegalStateException e) {
            return BaseResponse.failure("INVALID_STATE", e.getMessage());
        } catch (Exception e) {
            log.warn("[青龙调试工具] 输送线 {} 下发失败: {}", deviceId, e.getMessage());
            return BaseResponse.failure("WRITE_ERROR", e.getMessage());
        }
    }
}
