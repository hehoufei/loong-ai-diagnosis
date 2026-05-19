package cn.aimstek.loong.aidiag.rule;

import cn.aimstek.loong.aidiag.context.DiagnosisContext;
import cn.aimstek.loong.aidiag.dto.DiagnoseResponse;

/**
 * 规则引擎接口。
 * 负责按优先级执行规则，返回第一条匹配的诊断结果。
 */
public interface RuleEngine {

    /**
     * 对诊断上下文执行规则匹配
     * @param context 诊断上下文
     * @return 诊断结果（永不返回null）
     */
    DiagnoseResponse evaluate(DiagnosisContext context);
}
