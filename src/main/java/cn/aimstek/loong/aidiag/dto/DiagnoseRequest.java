package cn.aimstek.loong.aidiag.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class DiagnoseRequest {
    @Schema(description = "任务ID或WMS任务号", requiredMode = Schema.RequiredMode.REQUIRED)
    private String taskId;

    @Schema(description = "环境标识（可选，预留）")
    private String env;
}
