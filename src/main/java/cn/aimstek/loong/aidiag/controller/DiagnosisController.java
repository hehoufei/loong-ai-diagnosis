package cn.aimstek.loong.aidiag.controller;

import cn.aimstek.loong.aidiag.dto.DiagnoseRequest;
import cn.aimstek.loong.aidiag.dto.DiagnoseResponse;
import cn.aimstek.loong.aidiag.service.DiagnosisService;
import cn.aimstek.loong.aidiag.common.BaseResponse;
import cn.aimstek.loong.aidiag.common.Response;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/diagnosis")
public class DiagnosisController {

    private final DiagnosisService diagnosisService;

    @PostMapping("/task")
    public Response<DiagnoseResponse> diagnose(@RequestBody DiagnoseRequest request) {
        DiagnoseResponse result = diagnosisService.diagnose(request);
        return BaseResponse.success(result);
    }
}
