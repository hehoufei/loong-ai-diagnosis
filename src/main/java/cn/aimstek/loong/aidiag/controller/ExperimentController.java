package cn.aimstek.loong.aidiag.controller;

import cn.aimstek.loong.aidiag.common.BaseResponse;
import cn.aimstek.loong.aidiag.common.Response;
import cn.aimstek.loong.aidiag.experiment.ExperimentProfile;
import cn.aimstek.loong.aidiag.experiment.ExperimentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/experiments")
public class ExperimentController {

    private final ExperimentService experimentService;

    /**
     * POST /api/v1/experiments
     * 保存当前配置为新实验方案
     */
    @PostMapping
    public ResponseEntity<Response<ExperimentProfile>> saveProfile(@RequestBody Map<String, String> body) {
        try {
            String name = body.get("name");
            String description = body.get("description");
            if (name == null || name.isBlank()) {
                return ResponseEntity.badRequest()
                        .body(BaseResponse.failure("INVALID_PARAM", "参数 name 不能为空"));
            }
            ExperimentProfile profile = experimentService.saveCurrentAsProfile(name, description);
            return ResponseEntity.ok(BaseResponse.success(profile));
        } catch (Exception e) {
            log.error("保存实验方案失败", e);
            return ResponseEntity.internalServerError()
                    .body(BaseResponse.failure("SAVE_PROFILE_ERROR", "保存实验方案失败: " + e.getMessage()));
        }
    }

    /**
     * GET /api/v1/experiments
     * 列出所有实验方案
     */
    @GetMapping
    public Response<List<ExperimentProfile>> listProfiles() {
        try {
            return BaseResponse.success(experimentService.listProfiles());
        } catch (Exception e) {
            log.error("列出实验方案失败", e);
            return BaseResponse.failure("LIST_PROFILES_ERROR", "列出实验方案失败: " + e.getMessage());
        }
    }

    /**
     * GET /api/v1/experiments/compare
     * 对比两个实验方案（放在 /{id} 之前，避免路由冲突）
     */
    @GetMapping("/compare")
    public ResponseEntity<Response<Map<String, Object>>> compareProfiles(
            @RequestParam(value = "a", required = false) String idA,
            @RequestParam(value = "b", required = false) String idB) {
        try {
            if (idA == null || idA.isBlank() || idB == null || idB.isBlank()) {
                return ResponseEntity.badRequest()
                        .body(BaseResponse.failure("INVALID_PARAM", "参数 a 和 b 不能为空"));
            }
            Map<String, Object> comparison = experimentService.compareProfiles(idA, idB);
            return ResponseEntity.ok(BaseResponse.success(comparison));
        } catch (RuntimeException e) {
            if (e.getMessage() != null && e.getMessage().contains("不存在")) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(BaseResponse.failure("PROFILE_NOT_FOUND", e.getMessage()));
            }
            log.error("对比实验方案失败", e);
            return ResponseEntity.internalServerError()
                    .body(BaseResponse.failure("COMPARE_ERROR", "对比实验方案失败: " + e.getMessage()));
        }
    }

    /**
     * GET /api/v1/experiments/{id}
     * 获取方案详情（含统计快照）
     */
    @GetMapping("/{id}")
    public ResponseEntity<Response<ExperimentProfile>> getProfile(@PathVariable String id) {
        try {
            ExperimentProfile profile = experimentService.getProfile(id);
            return ResponseEntity.ok(BaseResponse.success(profile));
        } catch (RuntimeException e) {
            if (e.getMessage() != null && e.getMessage().contains("不存在")) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(BaseResponse.failure("PROFILE_NOT_FOUND", e.getMessage()));
            }
            log.error("获取实验方案失败: id={}", id, e);
            return ResponseEntity.internalServerError()
                    .body(BaseResponse.failure("GET_PROFILE_ERROR", "获取实验方案失败: " + e.getMessage()));
        }
    }

    /**
     * DELETE /api/v1/experiments/{id}
     * 删除实验方案
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Response<Void>> deleteProfile(@PathVariable String id) {
        try {
            experimentService.deleteProfile(id);
            return ResponseEntity.ok(BaseResponse.success(null));
        } catch (RuntimeException e) {
            if (e.getMessage() != null && e.getMessage().contains("不存在")) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(BaseResponse.failure("PROFILE_NOT_FOUND", e.getMessage()));
            }
            log.error("删除实验方案失败: id={}", id, e);
            return ResponseEntity.internalServerError()
                    .body(BaseResponse.failure("DELETE_PROFILE_ERROR", "删除实验方案失败: " + e.getMessage()));
        }
    }

    /**
     * POST /api/v1/experiments/{id}/apply
     * 应用方案配置到规则引擎
     */
    @PostMapping("/{id}/apply")
    public ResponseEntity<Response<Void>> applyProfile(@PathVariable String id) {
        try {
            experimentService.applyProfile(id);
            return ResponseEntity.ok(BaseResponse.success(null));
        } catch (RuntimeException e) {
            if (e.getMessage() != null && e.getMessage().contains("不存在")) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(BaseResponse.failure("PROFILE_NOT_FOUND", e.getMessage()));
            }
            log.error("应用实验方案失败: id={}", id, e);
            return ResponseEntity.internalServerError()
                    .body(BaseResponse.failure("APPLY_PROFILE_ERROR", "应用实验方案失败: " + e.getMessage()));
        }
    }

    /**
     * POST /api/v1/experiments/{id}/snapshot
     * 更新方案统计快照为当前统计
     */
    @PostMapping("/{id}/snapshot")
    public ResponseEntity<Response<Void>> takeSnapshot(@PathVariable String id) {
        try {
            experimentService.takeStatisticsSnapshot(id);
            return ResponseEntity.ok(BaseResponse.success(null));
        } catch (RuntimeException e) {
            if (e.getMessage() != null && e.getMessage().contains("不存在")) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(BaseResponse.failure("PROFILE_NOT_FOUND", e.getMessage()));
            }
            log.error("更新统计快照失败: id={}", id, e);
            return ResponseEntity.internalServerError()
                    .body(BaseResponse.failure("SNAPSHOT_ERROR", "更新统计快照失败: " + e.getMessage()));
        }
    }
}
