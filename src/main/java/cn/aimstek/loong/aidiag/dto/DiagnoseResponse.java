package cn.aimstek.loong.aidiag.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class DiagnoseResponse {
    private String summary;
    private List<RootCauseItem> rootCauses = new ArrayList<>();
    private List<String> actions = new ArrayList<>();
    private List<RelevantDoc> relevantDocs = new ArrayList<>();

    /**
     * 分析范围：single / global
     */
    private String analysisScope;

    /**
     * 全局分析快照，单任务模式下可为空
     */
    private TaskRelationSnapshot relationSnapshot;

    /**
     * 诊断模式：rule / llm / hybrid / fallback
     * 旧前端可忽略该字段，新前端可逐步接入。
     */
    private String diagnosisMode;

    /**
     * 诊断置信度，范围 0.0 ~ 1.0
     */
    private Double confidence;

    /**
     * 链路追踪ID，便于定位一次诊断的完整过程
     */
    private String traceId;
}
