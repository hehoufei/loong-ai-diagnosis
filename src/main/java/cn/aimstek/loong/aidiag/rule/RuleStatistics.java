package cn.aimstek.loong.aidiag.rule;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 规则统计数据模型
 */
@Data
public class RuleStatistics {
    private String ruleName;                                    // 规则名称
    private String description;                                  // 规则描述
    private int priority;                                       // 当前优先级
    private boolean enabled;                                    // 是否启用
    private AtomicLong hitCount = new AtomicLong(0);           // 命中次数
    private AtomicLong totalMatchTimeMs = new AtomicLong(0);   // 累计匹配耗时(ms)
    private AtomicLong totalDiagnoseTimeMs = new AtomicLong(0); // 累计诊断耗时(ms)
    private volatile LocalDateTime lastHitTime;                 // 最后命中时间
    private AtomicLong matchErrorCount = new AtomicLong(0);    // 匹配异常次数
    private AtomicLong diagnoseErrorCount = new AtomicLong(0); // 诊断异常次数
    
    public RuleStatistics(String ruleName, int priority, boolean enabled) {
        this.ruleName = ruleName;
        this.priority = priority;
        this.enabled = enabled;
    }
    
    /** 记录一次命中 */
    public void recordHit() {
        hitCount.incrementAndGet();
        lastHitTime = LocalDateTime.now();
    }
    
    /** 记录匹配耗时 */
    public void addMatchTime(long ms) {
        totalMatchTimeMs.addAndGet(ms);
    }
    
    /** 记录诊断耗时 */
    public void addDiagnoseTime(long ms) {
        totalDiagnoseTimeMs.addAndGet(ms);
    }
    
    /** 记录匹配异常 */
    public void recordMatchError() {
        matchErrorCount.incrementAndGet();
    }
    
    /** 记录诊断异常 */
    public void recordDiagnoseError() {
        diagnoseErrorCount.incrementAndGet();
    }
    
    /** 重置统计 */
    public void reset() {
        hitCount.set(0);
        totalMatchTimeMs.set(0);
        totalDiagnoseTimeMs.set(0);
        lastHitTime = null;
        matchErrorCount.set(0);
        diagnoseErrorCount.set(0);
    }
    
    /** 获取平均匹配耗时 */
    public double getAvgMatchTimeMs() {
        long hits = hitCount.get();
        return hits > 0 ? (double) totalMatchTimeMs.get() / hits : 0;
    }
    
    /** 获取平均诊断耗时 */
    public double getAvgDiagnoseTimeMs() {
        long hits = hitCount.get();
        return hits > 0 ? (double) totalDiagnoseTimeMs.get() / hits : 0;
    }
}
