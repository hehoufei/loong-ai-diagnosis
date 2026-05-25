package cn.aimstek.loong.aidiag.controller;

import cn.aimstek.loong.aidiag.common.BaseResponse;
import cn.aimstek.loong.aidiag.common.Response;
import cn.aimstek.loong.aidiag.dto.DiagnoseResponse;
import cn.aimstek.loong.aidiag.service.DiagnosisStreamService;
import cn.aimstek.loong.aidiag.service.SshLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/diagnosis")
public class DiagnosisStreamController {

    private final DiagnosisStreamService streamService;
    private final SshLogService sshLogService;

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter diagnoseStream(@RequestParam String taskId,
                                     @RequestParam(required = false) String env) {
        SseEmitter emitter = new SseEmitter(120_000L); // 2分钟超时（AI分析可能较慢）
        streamService.diagnoseAsync(emitter, taskId, env);
        return emitter;
    }

    @PostMapping("/ai-analyze")
    public Response<DiagnoseResponse> aiAnalyze(@RequestParam String taskId,
                                                @RequestParam(required = false) String env) {
        try {
            DiagnoseResponse result = streamService.aiAnalyze(taskId, env);
            return BaseResponse.success(result);
        } catch (Exception e) {
            log.error("AI分析失败: taskId={}", taskId, e);
            return BaseResponse.failure("AI_ANALYZE_ERROR", "AI分析失败: " + e.getMessage());
        }
    }

    @GetMapping(value = "/ai-analyze-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter aiAnalyzeStream(@RequestParam String taskId,
                                       @RequestParam(required = false) String env) {
        SseEmitter emitter = new SseEmitter(180_000L); // 3分钟超时
        streamService.aiAnalyzeStream(emitter, taskId, env);
        return emitter;
    }

    @GetMapping("/logs/query")
    public Response<List<String>> queryPlatformLogs(
            @RequestParam String taskNo,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(required = false) List<String> keywords,
            @RequestParam(required = false) String env) {
        try {
            List<String> extras = keywords != null ? keywords : java.util.Collections.emptyList();
            List<String> logs = sshLogService.queryLogs(taskNo, extras, startTime, endTime, env);
            return BaseResponse.success(logs);
        } catch (Exception e) {
            log.error("平台日志查询失败: taskNo={}", taskNo, e);
            return BaseResponse.failure("SSH_LOG_ERROR", "日志查询失败: " + e.getMessage());
        }
    }
}
