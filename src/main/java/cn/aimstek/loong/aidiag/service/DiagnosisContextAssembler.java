package cn.aimstek.loong.aidiag.service;

import cn.aimstek.loong.aidiag.client.LogClient;
import cn.aimstek.loong.aidiag.client.TaskClient;
import cn.aimstek.loong.aidiag.dto.DiagnoseRequest;
import cn.aimstek.loong.aidiag.dto.PointConflict;
import cn.aimstek.loong.aidiag.dto.TaskDetail;
import cn.aimstek.loong.aidiag.rule.DiagnosisContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 诊断上下文组装器。
 * 负责聚合任务详情、日志、冲突点等信息，供规则与大模型共用。
 */
@Service
@RequiredArgsConstructor
public class DiagnosisContextAssembler {

    private final TaskClient taskClient;
    private final LogClient logClient;
    private final DocSearchService docSearchService;

    public DiagnosisContext assemble(DiagnoseRequest request) {
        TaskDetail taskDetail = taskClient.getTaskDetail(request.getTaskNo(), request.getEnv());
        List<String> logs = safeLogs(logClient.queryLogs(request.getTaskNo(), request.getEnv()));
        List<String> relevantDocs = buildRelevantDocs(taskDetail, logs);

        return DiagnosisContext.builder()
                .detail(taskDetail)
                .logs(logs)
                .conflicts(Collections.<PointConflict>emptyList())
                .relevantDocs(new ArrayList<>(relevantDocs))
                .traceId(buildTraceId(request.getTaskNo()))
                .diagnosisMode("hybrid")
                .build();
    }

    private List<String> buildRelevantDocs(TaskDetail taskDetail, List<String> logs) {
        try {
            if (taskDetail == null) {
                return Collections.emptyList();
            }
            return docSearchService.preSearchDocs(taskDetail, logs);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private List<String> safeLogs(List<String> logs) {
        if (CollectionUtils.isEmpty(logs)) {
            return Collections.emptyList();
        }
        List<String> cleaned = new ArrayList<>(logs.size());
        for (String line : logs) {
            if (StringUtils.hasText(line)) {
                cleaned.add(line);
            }
        }
        return cleaned;
    }

    private String buildTraceId(String taskId) {
        return (StringUtils.hasText(taskId) ? taskId : "unknown") + "-" + UUID.randomUUID();
    }
}
