package cn.aimstek.loong.aidiag.service;

import cn.aimstek.loong.aidiag.dto.TaskDetail;
import cn.aimstek.loong.aidiag.dto.TaskRelationSnapshot;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class GlobalDiagnosisContext {
    private TaskDetail focusTask;
    @Builder.Default
    private List<TaskDetail> relatedTasks = new ArrayList<>();
    private TaskRelationSnapshot relationSnapshot;
    @Builder.Default
    private List<String> evidence = new ArrayList<>();
    private String traceId;
    private String diagnosisMode;
    private LocalDateTime windowStart;
    private LocalDateTime windowEnd;
}
