package cn.aimstek.loong.aidiag.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 诊断请求入参：仅需要一个 taskId。
 */
@Data
@Schema(description = "诊断请求")
public class DiagnoseRequest {

    @NotBlank(message = "taskId不能为空")
    @Schema(description = "WCS 任务号（taskNo）", requiredMode = Schema.RequiredMode.REQUIRED, example = "WMS_TASK_001")
    private String taskId;
}
