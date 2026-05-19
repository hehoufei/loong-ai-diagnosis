package cn.aimstek.loong.aidiag.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 诊断结果出参。
 */
@Data
@Schema(description = "诊断结果")
public class DiagnoseResponse {

    @Schema(description = "一句话根因摘要")
    private String summary;

    @Schema(description = "根因列表")
    private List<RootCauseItem> rootCauses = new ArrayList<>();

    @Schema(description = "建议操作列表")
    private List<String> actions = new ArrayList<>();

    @Schema(description = "诊断模式：rule / llm / fallback")
    private String diagnosisMode;

    @Schema(description = "诊断置信度，范围 0.0 ~ 1.0")
    private Double confidence;

    @Schema(description = "链路追踪ID")
    private String traceId;
}
