package cn.aimstek.loong.aidiag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Data
@Configuration
@ConfigurationProperties(prefix = "diagnosis")
public class DiagnosisPromptProperties {

    private String role = "";
    private String businessContext = "";
    private List<LifecycleRule> lifecycle = new ArrayList<>();
    private List<DecisionStep> decisionTree = new ArrayList<>();
    private List<FaultScenario> faultScenarios = new ArrayList<>();
    private String conflictHint = "";
    private String outputRequirement = "";

    @Data
    public static class LifecycleRule {
        private String title;
        private String content;
    }

    @Data
    public static class DecisionStep {
        private int step;
        private String title;
        private List<DecisionRule> rules = new ArrayList<>();
    }

    @Data
    public static class DecisionRule {
        private String condition;
        private String conclusion;
        private String causes;
    }

    @Data
    public static class FaultScenario {
        private String name;
        private String description;
    }
}
