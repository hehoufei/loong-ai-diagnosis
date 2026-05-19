package cn.aimstek.loong.aidiag.controller;

import cn.aimstek.loong.aidiag.common.BaseResponse;
import cn.aimstek.loong.aidiag.common.Response;
import cn.aimstek.loong.aidiag.dto.DiagnoseRequest;
import cn.aimstek.loong.aidiag.dto.DiagnoseResponse;
import cn.aimstek.loong.aidiag.facade.DiagnosisFacade;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 任务诊断 REST 入口。
 * 唯一接口：POST /api/v1/diagnosis/analyze
 */
@Tag(name = "诊断接口", description = "WCS 任务诊断")
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/diagnosis")
public class DiagnosisController {

    private final DiagnosisFacade diagnosisFacade;

    @Operation(summary = "分析任务", description = "输入 taskId，返回诊断结果")
    @PostMapping("/analyze")
    public Response<DiagnoseResponse> analyze(@Valid @RequestBody DiagnoseRequest request) {
        DiagnoseResponse result = diagnosisFacade.diagnose(request);
        return BaseResponse.success(result);
    }
}
