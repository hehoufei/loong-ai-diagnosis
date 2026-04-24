package cn.aimstek.loong.aidiag.service;

import cn.aimstek.loong.aidiag.rule.RuleProperties;
import org.springframework.stereotype.Service;

/**
 * 规则引擎配置快照服务。
 * 负责将当前规则配置转为可比较的快照字符串，用于检测配置变更。
 */
@Service
public class RuleEngineSnapshotService {

    public String buildSnapshot(RuleProperties props) {
        StringBuilder sb = new StringBuilder();
        props.getRules().entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .forEach(e -> {
                    RuleProperties.RuleConfig c = e.getValue();
                    sb.append(e.getKey())
                      .append("=enabled:").append(c.isEnabled())
                      .append(",priority:").append(c.getPriority())
                      .append(",params:").append(c.getParams())
                      .append(",desc:").append(c.getDescription() == null ? "" : c.getDescription())
                      .append(",condition:").append(c.getCondition() == null ? "" : c.getCondition());
                    RuleProperties.OutputConfig out = c.getOutput();
                    if (out != null) {
                        sb.append(",out.summary:").append(out.getSummary() == null ? "" : out.getSummary())
                          .append(",out.rootCauses:").append(out.getRootCauses())
                          .append(",out.actions:").append(out.getActions());
                    }
                    sb.append(";");
                });
        sb.append("docSearch=topK:").append(props.getDocSearch().getTopK())
                .append(",threshold:").append(props.getDocSearch().getSimilarityThreshold())
                .append(",preSearch:").append(props.getDocSearch().isPreSearchEnabled());
        return sb.toString();
    }
}
