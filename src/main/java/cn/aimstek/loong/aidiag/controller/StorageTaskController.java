package cn.aimstek.loong.aidiag.controller;

import cn.aimstek.loong.aidiag.common.BaseResponse;
import cn.aimstek.loong.aidiag.common.Response;
import cn.aimstek.loong.aidiag.storagetask.StorageTaskRunner;
import cn.aimstek.loong.aidiag.storagetask.dto.StorageTaskConfig;
import cn.aimstek.loong.aidiag.storagetask.dto.StorageTaskRunnerState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 立库库位循环任务测试 API
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/storage-task")
@RequiredArgsConstructor
public class StorageTaskController {

    private final StorageTaskRunner runner;

    @GetMapping("/config")
    public Response<StorageTaskConfig> getConfig() {
        return BaseResponse.success(runner.getConfig());
    }

    @PutMapping("/config")
    public Response<Void> updateConfig(@RequestBody StorageTaskConfig cfg) {
        try {
            runner.updateConfig(cfg);
            return BaseResponse.success(null);
        } catch (IllegalStateException e) {
            return BaseResponse.failure("INVALID_STATE", e.getMessage());
        } catch (Exception e) {
            log.error("更新立库测试配置失败", e);
            return BaseResponse.failure("UPDATE_CONFIG_ERROR", e.getMessage());
        }
    }

    @GetMapping("/state")
    public Response<StorageTaskRunnerState> getState() {
        return BaseResponse.success(runner.getState());
    }

    @PostMapping("/start")
    public Response<Void> start() {
        try {
            runner.start();
            return BaseResponse.success(null);
        } catch (IllegalStateException e) {
            return BaseResponse.failure("INVALID_STATE", e.getMessage());
        } catch (Exception e) {
            log.error("启动立库测试失败", e);
            return BaseResponse.failure("START_ERROR", e.getMessage());
        }
    }

    @PostMapping("/pause")
    public Response<Void> pause() {
        try {
            runner.pause();
            return BaseResponse.success(null);
        } catch (Exception e) {
            return BaseResponse.failure("PAUSE_ERROR", e.getMessage());
        }
    }

    @PostMapping("/skip")
    public Response<Void> skip() {
        try {
            runner.skipCurrent();
            return BaseResponse.success(null);
        } catch (IllegalStateException e) {
            return BaseResponse.failure("INVALID_STATE", e.getMessage());
        } catch (Exception e) {
            return BaseResponse.failure("SKIP_ERROR", e.getMessage());
        }
    }

    @PostMapping("/reset")
    public Response<Void> reset() {
        try {
            runner.reset();
            return BaseResponse.success(null);
        } catch (IllegalStateException e) {
            return BaseResponse.failure("INVALID_STATE", e.getMessage());
        } catch (Exception e) {
            return BaseResponse.failure("RESET_ERROR", e.getMessage());
        }
    }

    @PostMapping("/regenerate-codes")
    public Response<Integer> regenerateCodes() {
        try {
            runner.regenerateValidCodes();
            return BaseResponse.success(runner.getState().getValidCodes().size());
        } catch (Exception e) {
            log.error("重新生成有效库位失败", e);
            return BaseResponse.failure("REGENERATE_ERROR", e.getMessage());
        }
    }

    @PostMapping("/test-db")
    public Response<Void> testDb() {
        try {
            runner.testDb();
            return BaseResponse.success(null);
        } catch (Exception e) {
            return BaseResponse.failure("DB_ERROR", e.getMessage());
        }
    }

    @PostMapping("/clear-visited")
    public Response<Void> clearVisited() {
        try {
            runner.clearVisited();
            return BaseResponse.success(null);
        } catch (Exception e) {
            return BaseResponse.failure("CLEAR_ERROR", e.getMessage());
        }
    }

    /** 重试当前未完成任务 (addTask 失败后用户处理掉冲突再重发) */
    @PostMapping("/retry")
    public Response<Void> retryCurrent() {
        try {
            runner.retryCurrent();
            return BaseResponse.success(null);
        } catch (IllegalStateException e) {
            return BaseResponse.failure("INVALID_STATE", e.getMessage());
        } catch (Exception e) {
            log.error("重试当前任务失败", e);
            return BaseResponse.failure("RETRY_ERROR", e.getMessage());
        }
    }

    /** 下载完整任务历史 CSV (UTF-8 BOM, Excel 直接打开) */
    @GetMapping("/history.csv")
    public org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> downloadHistory() {
        java.io.File f = runner.historyFile();
        if (!f.exists()) {
            return org.springframework.http.ResponseEntity.notFound().build();
        }
        org.springframework.core.io.FileSystemResource res = new org.springframework.core.io.FileSystemResource(f);
        String fname = "storage-task-history-" +
                java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                + ".csv";
        return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + fname + "\"")
                .header(org.springframework.http.HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
                .contentLength(f.length())
                .body(res);
    }

    @PostMapping("/history/clear")
    public Response<Void> clearHistory() {
        try {
            runner.clearHistoryCsv();
            return BaseResponse.success(null);
        } catch (Exception e) {
            return BaseResponse.failure("CLEAR_HISTORY_ERROR", e.getMessage());
        }
    }

    /**
     * 预览未来 N 个任务 (干跑), 不下发不修改任何状态.
     * 用于在 UI 上看路径再决定要不要 启动.
     */
    @GetMapping("/preview")
    public Response<java.util.List<cn.aimstek.loong.aidiag.storagetask.dto.StorageTaskRecord>> preview(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "30") int n) {
        try {
            return BaseResponse.success(runner.previewTasks(n));
        } catch (IllegalStateException e) {
            return BaseResponse.failure("INVALID_STATE", e.getMessage());
        } catch (Exception e) {
            log.error("预览任务失败", e);
            return BaseResponse.failure("PREVIEW_ERROR", e.getMessage());
        }
    }

    /**
     * 校验配置中的两段 SQL 是否合法 (仅做语法形态校验, 不实际执行).
     * 请求体: 完整 StorageTaskConfig (用于试改还没保存的草稿)
     */
    @PostMapping("/validate-sql")
    public Response<Void> validateSql(@RequestBody StorageTaskConfig draft) {
        try {
            cn.aimstek.loong.aidiag.storagetask.StorageDb.validateSelectSql(
                    "validateLocationSql", draft.getValidateLocationSql());
            if (draft.getValidateLocationSql() == null
                    || !draft.getValidateLocationSql().contains(
                        cn.aimstek.loong.aidiag.storagetask.StorageDb.CODES_PLACEHOLDER)) {
                return BaseResponse.failure("INVALID_SQL",
                        "validateLocationSql 必须包含占位符 {CODES}");
            }
            cn.aimstek.loong.aidiag.storagetask.StorageDb.validateSelectSql(
                    "queryTaskStateSql", draft.getQueryTaskStateSql());
            return BaseResponse.success(null);
        } catch (IllegalArgumentException e) {
            return BaseResponse.failure("INVALID_SQL", e.getMessage());
        } catch (Exception e) {
            return BaseResponse.failure("VALIDATE_SQL_ERROR", e.getMessage());
        }
    }
}
