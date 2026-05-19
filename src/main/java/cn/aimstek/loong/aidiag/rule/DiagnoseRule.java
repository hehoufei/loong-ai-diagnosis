package cn.aimstek.loong.aidiag.rule;

import cn.aimstek.loong.aidiag.context.DiagnosisContext;
import cn.aimstek.loong.aidiag.dto.DiagnoseResponse;

/**
 * 诊断规则接口。
 * 每条规则负责匹配一个特定的诊断场景，并生成对应的诊断结果。
 */
public interface DiagnoseRule {

    /** 规则唯一标识名 */
    String getName();

    /** 优先级（数字越大越先执行） */
    int getPriority();

    /** 是否匹配当前诊断上下文 */
    boolean match(DiagnosisContext context);

    /** 执行诊断，生成诊断结果 */
    DiagnoseResponse diagnose(DiagnosisContext context);

    /** 规则描述 */
    default String getDescription() {
        return "";
    }
}
