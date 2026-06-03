package cn.aimstek.loong.aidiag.controller;

import cn.aimstek.loong.aidiag.common.BaseResponse;
import cn.aimstek.loong.aidiag.common.Response;
import cn.aimstek.loong.aidiag.storagetask.StorageDb;
import cn.aimstek.loong.aidiag.storagetask.StorageTaskRunner;
import cn.aimstek.loong.aidiag.storagetask.StorageTaskRunnerManager;
import cn.aimstek.loong.aidiag.storagetask.dto.StorageTaskConfig;
import cn.aimstek.loong.aidiag.storagetask.dto.StorageTaskRecord;
import cn.aimstek.loong.aidiag.storagetask.dto.StorageTaskRunnerState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 立库库位循环任务测试 API (多巷道版本)
 *
 * 路径规则:
 *   /api/v1/storage-task/overview          总览所有巷道
 *   /api/v1/storage-task/{aisle}/state     指定巷道的状态
 *   /api/v1/storage-task/{aisle}/start     启动指定巷道
 *   ...
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/storage-task")
@RequiredArgsConstructor
public class StorageTaskController {

    private final StorageTaskRunnerManager manager;

    // ============== 总览 ==============

    @GetMapping("/overview")
    public Response<List<StorageTaskRunnerManager.AisleOverview>> overview() {
        return BaseResponse.success(manager.getOverview());
    }

    @GetMapping("/aisles")
    public Response<List<Integer>> aisles() {
        return BaseResponse.success(manager.getLoadedAisles());
    }

    // ============== 按巷道操作 ==============

    @GetMapping("/{aisle}/config")
    public Response<StorageTaskConfig> getConfig(@PathVariable int aisle) {
        return BaseResponse.success(runner(aisle).getConfig());
    }

    @PutMapping("/{aisle}/config")
    public Response<Void> updateConfig(@PathVariable int aisle, @RequestBody StorageTaskConfig cfg) {
        try {
            runner(aisle).updateConfig(cfg);
            return BaseResponse.success(null);
        } catch (IllegalStateException e) {
            return BaseResponse.failure("INVALID_STATE", e.getMessage());
        } catch (Exception e) {
            log.error("更新巷道{}配置失败", aisle, e);
            return BaseResponse.failure("UPDATE_CONFIG_ERROR", e.getMessage());
        }
    }

    @GetMapping("/{aisle}/state")
    public Response<StorageTaskRunnerState> getState(@PathVariable int aisle) {
        return BaseResponse.success(runner(aisle).getState());
    }

    @PostMapping("/{aisle}/start")
    public Response<Void> start(@PathVariable int aisle) {
        try {
            runner(aisle).start();
            return BaseResponse.success(null);
        } catch (IllegalStateException e) {
            return BaseResponse.failure("INVALID_STATE", e.getMessage());
        } catch (Exception e) {
            log.error("启动巷道{}失败", aisle, e);
            return BaseResponse.failure("START_ERROR", e.getMessage());
        }
    }

    @PostMapping("/{aisle}/pause")
    public Response<Void> pause(@PathVariable int aisle) {
        try {
            runner(aisle).pause();
            return BaseResponse.success(null);
        } catch (Exception e) {
            return BaseResponse.failure("PAUSE_ERROR", e.getMessage());
        }
    }

    @PostMapping("/{aisle}/skip")
    public Response<Void> skip(@PathVariable int aisle) {
        try {
            runner(aisle).skipCurrent();
            return BaseResponse.success(null);
        } catch (IllegalStateException e) {
            return BaseResponse.failure("INVALID_STATE", e.getMessage());
        } catch (Exception e) {
            return BaseResponse.failure("SKIP_ERROR", e.getMessage());
        }
    }

    @PostMapping("/{aisle}/reset")
    public Response<Void> reset(@PathVariable int aisle) {
        try {
            runner(aisle).reset();
            return BaseResponse.success(null);
        } catch (IllegalStateException e) {
            return BaseResponse.failure("INVALID_STATE", e.getMessage());
        } catch (Exception e) {
            return BaseResponse.failure("RESET_ERROR", e.getMessage());
        }
    }

    @PostMapping("/{aisle}/regenerate-codes")
    public Response<Integer> regenerateCodes(@PathVariable int aisle) {
        try {
            StorageTaskRunner r = runner(aisle);
            r.regenerateValidCodes();
            return BaseResponse.success(r.getState().getValidCodes().size());
        } catch (Exception e) {
            log.error("巷道{}重新生成有效库位失败", aisle, e);
            return BaseResponse.failure("REGENERATE_ERROR", e.getMessage());
        }
    }

    @PostMapping("/{aisle}/test-db")
    public Response<Void> testDb(@PathVariable int aisle) {
        try {
            runner(aisle).testDb();
            return BaseResponse.success(null);
        } catch (Exception e) {
            return BaseResponse.failure("DB_ERROR", e.getMessage());
        }
    }

    @PostMapping("/{aisle}/clear-visited")
    public Response<Void> clearVisited(@PathVariable int aisle) {
        try {
            runner(aisle).clearVisited();
            return BaseResponse.success(null);
        } catch (Exception e) {
            return BaseResponse.failure("CLEAR_ERROR", e.getMessage());
        }
    }

    @PostMapping("/{aisle}/fix-alarm-dedup")
    public Response<Integer> fixAlarmDedup(@PathVariable int aisle) {
        try {
            int fixed = runner(aisle).fixAlarmDedup();
            return BaseResponse.success(fixed);
        } catch (Exception e) {
            return BaseResponse.failure("FIX_ALARM_ERROR", e.getMessage());
        }
    }

    @PostMapping("/{aisle}/retry")
    public Response<Void> retryCurrent(@PathVariable int aisle) {
        try {
            runner(aisle).retryCurrent();
            return BaseResponse.success(null);
        } catch (IllegalStateException e) {
            return BaseResponse.failure("INVALID_STATE", e.getMessage());
        } catch (Exception e) {
            log.error("巷道{}重试当前任务失败", aisle, e);
            return BaseResponse.failure("RETRY_ERROR", e.getMessage());
        }
    }

    @GetMapping("/{aisle}/history.csv")
    public ResponseEntity<org.springframework.core.io.Resource> downloadHistory(@PathVariable int aisle) {
        File f = runner(aisle).historyFile();
        if (!f.exists()) {
            return ResponseEntity.notFound().build();
        }
        FileSystemResource res = new FileSystemResource(f);
        String fname = "storage-task-history-aisle" + aisle + "-" +
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fname + "\"")
                .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
                .contentLength(f.length())
                .body(res);
    }

    @PostMapping("/{aisle}/history/clear")
    public Response<Void> clearHistory(@PathVariable int aisle) {
        try {
            runner(aisle).clearHistoryCsv();
            return BaseResponse.success(null);
        } catch (Exception e) {
            return BaseResponse.failure("CLEAR_HISTORY_ERROR", e.getMessage());
        }
    }

    @GetMapping("/{aisle}/preview")
    public Response<List<StorageTaskRecord>> preview(
            @PathVariable int aisle,
            @RequestParam(defaultValue = "30") int n) {
        try {
            return BaseResponse.success(runner(aisle).previewTasks(n));
        } catch (IllegalStateException e) {
            return BaseResponse.failure("INVALID_STATE", e.getMessage());
        } catch (Exception e) {
            log.error("巷道{}预览任务失败", aisle, e);
            return BaseResponse.failure("PREVIEW_ERROR", e.getMessage());
        }
    }

    @PostMapping("/{aisle}/validate-sql")
    public Response<Void> validateSql(@PathVariable int aisle, @RequestBody StorageTaskConfig draft) {
        try {
            StorageDb.validateSelectSql("validateLocationSql", draft.getValidateLocationSql());
            if (draft.getValidateLocationSql() == null
                    || !draft.getValidateLocationSql().contains(StorageDb.CODES_PLACEHOLDER)) {
                return BaseResponse.failure("INVALID_SQL",
                        "validateLocationSql 必须包含占位符 {CODES}");
            }
            StorageDb.validateSelectSql("queryTaskStateSql", draft.getQueryTaskStateSql());
            return BaseResponse.success(null);
        } catch (IllegalArgumentException e) {
            return BaseResponse.failure("INVALID_SQL", e.getMessage());
        } catch (Exception e) {
            return BaseResponse.failure("VALIDATE_SQL_ERROR", e.getMessage());
        }
    }

    // ============== 兼容旧路径 (无巷道参数, 默认 aisle=8) ==============

    @GetMapping("/config")
    public Response<StorageTaskConfig> getConfigLegacy() { return getConfig(8); }
    @PutMapping("/config")
    public Response<Void> updateConfigLegacy(@RequestBody StorageTaskConfig cfg) { return updateConfig(8, cfg); }
    @GetMapping("/state")
    public Response<StorageTaskRunnerState> getStateLegacy() { return getState(8); }
    @PostMapping("/start")
    public Response<Void> startLegacy() { return start(8); }
    @PostMapping("/pause")
    public Response<Void> pauseLegacy() { return pause(8); }
    @PostMapping("/skip")
    public Response<Void> skipLegacy() { return skip(8); }
    @PostMapping("/reset")
    public Response<Void> resetLegacy() { return reset(8); }
    @PostMapping("/regenerate-codes")
    public Response<Integer> regenerateCodesLegacy() { return regenerateCodes(8); }
    @PostMapping("/test-db")
    public Response<Void> testDbLegacy() { return testDb(8); }
    @PostMapping("/clear-visited")
    public Response<Void> clearVisitedLegacy() { return clearVisited(8); }
    @PostMapping("/retry")
    public Response<Void> retryCurrentLegacy() { return retryCurrent(8); }
    @GetMapping("/history.csv")
    public ResponseEntity<org.springframework.core.io.Resource> downloadHistoryLegacy() { return downloadHistory(8); }
    @PostMapping("/history/clear")
    public Response<Void> clearHistoryLegacy() { return clearHistory(8); }
    @GetMapping("/preview")
    public Response<List<StorageTaskRecord>> previewLegacy(@RequestParam(defaultValue = "30") int n) { return preview(8, n); }

    // ============== 内部 ==============

    private StorageTaskRunner runner(int aisle) {
        return manager.getOrCreateRunner(aisle);
    }
}
