package cn.aimstek.loong.aidiag.service;

import cn.aimstek.loong.aidiag.client.TaskClient;
import cn.aimstek.loong.aidiag.dto.DiagnoseRequest;
import cn.aimstek.loong.aidiag.dto.ResourceBottleneck;
import cn.aimstek.loong.aidiag.dto.TaskDetail;
import cn.aimstek.loong.aidiag.dto.TaskRelationSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class GlobalDiagnosisContextAssembler {

    private static final int DEFAULT_WINDOW_MINUTES = 30;

    private final TaskClient taskClient;
    private final BlockageAnalyzer blockageAnalyzer;

    public GlobalDiagnosisContext assemble(DiagnoseRequest request) {
        TaskDetail focusTask = taskClient.getTaskDetail(request.getTaskId(), request.getEnv());
        LocalDateTime anchor = focusTask.getCreateTime() != null ? focusTask.getCreateTime() : LocalDateTime.now();
        int windowMinutes = request.getTimeWindowMinutes() != null && request.getTimeWindowMinutes() > 0
                ? request.getTimeWindowMinutes()
                : DEFAULT_WINDOW_MINUTES;
        LocalDateTime windowStart = anchor.minusMinutes(windowMinutes);
        LocalDateTime windowEnd = anchor.plusMinutes(5);

        List<TaskDetail> relatedTasks = taskClient.findRelatedTasks(focusTask, windowStart, windowEnd, request.getEnv());
        TaskRelationSnapshot snapshot = blockageAnalyzer.analyze(focusTask, relatedTasks);
        List<String> evidence = buildEvidence(focusTask, snapshot, windowStart, windowEnd);

        return GlobalDiagnosisContext.builder()
                .focusTask(focusTask)
                .relatedTasks(relatedTasks)
                .relationSnapshot(snapshot)
                .evidence(evidence)
                .traceId(buildTraceId(focusTask.getTaskId()))
                .diagnosisMode("global-rule")
                .windowStart(windowStart)
                .windowEnd(windowEnd)
                .build();
    }

    private List<String> buildEvidence(TaskDetail focusTask, TaskRelationSnapshot snapshot, LocalDateTime windowStart, LocalDateTime windowEnd) {
        List<String> evidence = new ArrayList<>();
        evidence.add("分析任务: " + focusTask.getTaskId());
        evidence.add("时间窗: " + windowStart + " ~ " + windowEnd);
        evidence.add("候选关联任务数: " + snapshot.getRelatedTasks().size());
        if (StringUtils.hasText(snapshot.getDirectBlockerTaskId())) {
            evidence.add("直接阻塞候选: " + snapshot.getDirectBlockerTaskId());
        }
        if (StringUtils.hasText(snapshot.getRootBlockerTaskId())) {
            evidence.add("根阻塞候选: " + snapshot.getRootBlockerTaskId());
        }
        for (ResourceBottleneck bottleneck : snapshot.getBottleneckResources()) {
            evidence.add("瓶颈资源: " + bottleneck.getResourceType() + "=" + bottleneck.getResourceKey()
                    + ", impacted=" + bottleneck.getImpactedTaskCount());
        }
        return evidence;
    }

    private String buildTraceId(String taskId) {
        return (StringUtils.hasText(taskId) ? taskId : "unknown") + "-global-" + UUID.randomUUID();
    }
}
