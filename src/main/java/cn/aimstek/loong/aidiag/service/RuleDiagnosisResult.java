package cn.aimstek.loong.aidiag.service;

import cn.aimstek.loong.aidiag.dto.DiagnoseResponse;
import lombok.Data;

/**
 * 规则诊断执行结果。
 * 作为编排层与规则引擎之间的中间结果，便于后续融合、统计和调试。
 */
@Data
public class RuleDiagnosisResult {
    private boolean matched;
    private String ruleName;
    private int priority;
    private DiagnoseResponse response;
    private long matchTimeMs;
    private long diagnoseTimeMs;
    private String errorMessage;

    public static RuleDiagnosisResult notMatched() {
        RuleDiagnosisResult result = new RuleDiagnosisResult();
        result.setMatched(false);
        return result;
    }
}
