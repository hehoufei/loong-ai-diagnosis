package cn.aimstek.loong.aidiag.context;

import cn.aimstek.loong.aidiag.client.PlatformClient;
import cn.aimstek.loong.aidiag.client.dto.PlatformTask;
import cn.aimstek.loong.aidiag.client.dto.PlatformTaskGroup;
import cn.aimstek.loong.aidiag.exception.AiDiagnosisException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 诊断上下文组装器。
 * 从 PlatformClient 获取数据并组装为 DiagnosisContext。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DiagnosisContextAssembler {

    private final PlatformClient platformClient;

    /**
     * 组装诊断上下文。
     * @param taskNo 任务号
     * @return 诊断上下文
     * @throws AiDiagnosisException 任务不存在时抛 TASK_NOT_FOUND
     */
    public DiagnosisContext assemble(String taskNo) {
        String traceId = buildTraceId(taskNo);
        log.info("DIAG_START traceId={} taskNo={}", traceId, taskNo);

        // 1. 查询大任务（失败抛异常）
        PlatformTask task = platformClient.queryTask(taskNo);
        if (task == null) {
            throw new AiDiagnosisException("TASK_NOT_FOUND", "任务不存在: " + taskNo);
        }

        // 2. 查询子任务 + 指令 + 依赖关系
        PlatformClient.TaskItemBundle bundle = platformClient.queryTaskItemBundle(taskNo);

        // 3. 查询任务组（可选，失败不阻断）
        PlatformTaskGroup group = null;
        if (task.getGroupCode() != null && !task.getGroupCode().isEmpty()) {
            try {
                group = platformClient.queryTaskGroup(task.getGroupCode());
            } catch (Exception e) {
                log.warn("查询任务组失败（不阻断）: groupCode={}, error={}", task.getGroupCode(), e.getMessage());
            }
        }

        // 4. 组装上下文
        return DiagnosisContext.builder()
                .traceId(traceId)
                .task(task)
                .items(bundle.getItems())
                .relations(bundle.getRelations())
                .group(group)
                .now(LocalDateTime.now())
                .build();
    }

    private String buildTraceId(String taskNo) {
        return (taskNo != null ? taskNo : "unknown") + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
