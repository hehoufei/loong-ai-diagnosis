package cn.aimstek.loong.aidiag.service;

import cn.aimstek.loong.aidiag.dto.ResourceBottleneck;
import cn.aimstek.loong.aidiag.dto.TaskDetail;
import cn.aimstek.loong.aidiag.dto.TaskRelationEdge;
import cn.aimstek.loong.aidiag.dto.TaskRelationSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class BlockageAnalyzer {

    public TaskRelationSnapshot analyze(TaskDetail focusTask, List<TaskDetail> relatedTasks) {
        TaskRelationSnapshot snapshot = new TaskRelationSnapshot();
        snapshot.setFocusTaskId(focusTask.getTaskId());

        List<TaskDetail> sortedTasks = new ArrayList<>(relatedTasks);
        sortedTasks.sort(Comparator.comparing(TaskDetail::getCreateTime, Comparator.nullsLast(Comparator.naturalOrder())));

        TaskDetail directBlocker = selectDirectBlocker(focusTask, sortedTasks);
        TaskDetail rootBlocker = selectRootBlocker(sortedTasks, directBlocker);
        snapshot.setDirectBlockerTaskId(directBlocker != null ? directBlocker.getTaskId() : null);
        snapshot.setRootBlockerTaskId(rootBlocker != null ? rootBlocker.getTaskId() : null);

        for (TaskDetail candidate : sortedTasks) {
            snapshot.getRelatedTasks().add(toSummary(focusTask, candidate));
        }

        if (directBlocker != null) {
            snapshot.getDependencyEdges().add(new TaskRelationEdge(
                    directBlocker.getTaskId(),
                    focusTask.getTaskId(),
                    inferRelationType(focusTask, directBlocker),
                    relationResourceKey(focusTask, directBlocker),
                    buildDirectBlockDescription(focusTask, directBlocker)
            ));
        }
        if (rootBlocker != null && directBlocker != null && !Objects.equals(rootBlocker.getTaskId(), directBlocker.getTaskId())) {
            snapshot.getDependencyEdges().add(new TaskRelationEdge(
                    rootBlocker.getTaskId(),
                    directBlocker.getTaskId(),
                    "upstream_blocked",
                    relationResourceKey(rootBlocker, directBlocker),
                    buildRootBlockDescription(rootBlocker, directBlocker)
            ));
        }

        snapshot.setImpactedTaskIds(findImpactedTasks(focusTask, sortedTasks));
        snapshot.setBottleneckResources(findBottlenecks(focusTask, sortedTasks));
        return snapshot;
    }

    private TaskRelationSnapshot.TaskSummary toSummary(TaskDetail focusTask, TaskDetail candidate) {
        TaskRelationSnapshot.TaskSummary summary = new TaskRelationSnapshot.TaskSummary();
        String relationType = inferRelationType(focusTask, candidate);
        String blockageType = inferBlockageType(focusTask, candidate);
        summary.setTaskId(candidate.getTaskId());
        summary.setWmsTaskNo(candidate.getWmsTaskNo());
        summary.setTaskState(candidate.getTaskState());
        summary.setHandleState(candidate.getHandleState());
        summary.setContainerCode(candidate.getContainerCode());
        summary.setBusinessFrom(candidate.getBusinessFrom());
        summary.setBusinessTo(candidate.getBusinessTo());
        summary.setDeviceCode(extractPrimaryDeviceCode(candidate));
        summary.setRelationType(relationType);
        summary.setRelationReason(buildRelationReason(focusTask, candidate, relationType));
        summary.setBlockageType(blockageType);
        summary.setBlockageReason(buildBlockageReason(focusTask, candidate, blockageType));
        summary.setErrorMessage(candidate.getErrorMessage());
        return summary;
    }

    private TaskDetail selectDirectBlocker(TaskDetail focusTask, List<TaskDetail> candidates) {
        return candidates.stream()
                .filter(candidate -> isEarlierThanFocus(focusTask, candidate))
                .filter(candidate -> !isFinished(candidate))
                .filter(candidate -> hasStrongRelation(focusTask, candidate))
                .min(Comparator.comparingInt(candidate -> directBlockerScore(focusTask, candidate)))
                .orElse(null);
    }

    private TaskDetail selectRootBlocker(List<TaskDetail> candidates, TaskDetail directBlocker) {
        if (candidates.isEmpty()) {
            return directBlocker;
        }
        return candidates.stream()
                .filter(candidate -> !isFinished(candidate))
                .filter(candidate -> hasExplicitAbnormal(candidate) || "FAILED".equalsIgnoreCase(candidate.getTaskState()))
                .min(Comparator.comparingInt(this::rootBlockerScore)
                        .thenComparing(TaskDetail::getCreateTime, Comparator.nullsLast(Comparator.naturalOrder())))
                .orElse(directBlocker);
    }

    private List<String> findImpactedTasks(TaskDetail focusTask, List<TaskDetail> candidates) {
        List<String> impacted = new ArrayList<>();
        for (TaskDetail candidate : candidates) {
            if (!hasStrongRelation(focusTask, candidate)) {
                continue;
            }
            if (candidate.getCreateTime() != null && focusTask.getCreateTime() != null
                    && candidate.getCreateTime().isAfter(focusTask.getCreateTime())) {
                impacted.add(candidate.getTaskId());
            }
        }
        return impacted;
    }

    private List<ResourceBottleneck> findBottlenecks(TaskDetail focusTask, List<TaskDetail> candidates) {
        List<ResourceBottleneck> bottlenecks = new ArrayList<>();
        String containerCode = focusTask.getContainerCode();
        if (StringUtils.hasText(containerCode)) {
            int impacted = (int) candidates.stream().filter(candidate -> containerCode.equals(candidate.getContainerCode())).count();
            if (impacted > 0) {
                bottlenecks.add(new ResourceBottleneck("container", containerCode, "同容器链路存在关联任务，可能存在容器未释放", impacted));
            }
        }

        Set<String> pointKeys = new LinkedHashSet<>();
        addIfHasText(pointKeys, focusTask.getBusinessFrom());
        addIfHasText(pointKeys, focusTask.getDefiniteFrom());
        addIfHasText(pointKeys, focusTask.getBusinessTo());
        addIfHasText(pointKeys, focusTask.getDefiniteTo());
        for (String pointKey : pointKeys) {
            int impacted = (int) candidates.stream().filter(candidate -> pointKey.equals(candidate.getBusinessFrom())
                    || pointKey.equals(candidate.getDefiniteFrom())
                    || pointKey.equals(candidate.getBusinessTo())
                    || pointKey.equals(candidate.getDefiniteTo())).count();
            if (impacted > 0) {
                bottlenecks.add(new ResourceBottleneck("point", pointKey, "同点位存在排队或占用候选，可能存在点位未释放", impacted));
            }
        }

        String deviceCode = extractPrimaryDeviceCode(focusTask);
        if (StringUtils.hasText(deviceCode)) {
            int impacted = (int) candidates.stream().filter(candidate -> deviceCode.equals(extractPrimaryDeviceCode(candidate))).count();
            if (impacted > 0) {
                bottlenecks.add(new ResourceBottleneck("device", deviceCode, "同设备存在关联任务，可能存在设备繁忙或故障", impacted));
            }
        }
        return bottlenecks;
    }

    private int directBlockerScore(TaskDetail focusTask, TaskDetail candidate) {
        int score = 100;
        if (sameValue(extractPrimaryDeviceCode(focusTask), extractPrimaryDeviceCode(candidate))) {
            score -= 40;
        }
        if (sameValue(focusTask.getBusinessTo(), candidate.getBusinessTo()) || sameValue(focusTask.getDefiniteTo(), candidate.getDefiniteTo())) {
            score -= 30;
        }
        if (sameValue(focusTask.getContainerCode(), candidate.getContainerCode())) {
            score -= 20;
        }
        if (hasExplicitAbnormal(candidate)) {
            score -= 10;
        }
        return score;
    }

    private int rootBlockerScore(TaskDetail candidate) {
        int score = 100;
        if ("FAILED".equalsIgnoreCase(candidate.getTaskState())) {
            score -= 40;
        }
        if (hasExplicitAbnormal(candidate)) {
            score -= 30;
        }
        if (!isFinished(candidate)) {
            score -= 20;
        }
        return score;
    }

    private boolean hasExplicitAbnormal(TaskDetail candidate) {
        return StringUtils.hasText(candidate.getErrorMessage())
                || "FAILED".equalsIgnoreCase(candidate.getTaskState())
                || "ERROR".equalsIgnoreCase(candidate.getHandleState());
    }

    private boolean hasStrongRelation(TaskDetail focusTask, TaskDetail candidate) {
        return sameValue(focusTask.getContainerCode(), candidate.getContainerCode())
                || sameValue(focusTask.getBusinessFrom(), candidate.getBusinessFrom())
                || sameValue(focusTask.getDefiniteFrom(), candidate.getDefiniteFrom())
                || sameValue(focusTask.getBusinessTo(), candidate.getBusinessTo())
                || sameValue(focusTask.getDefiniteTo(), candidate.getDefiniteTo())
                || sameValue(extractPrimaryDeviceCode(focusTask), extractPrimaryDeviceCode(candidate));
    }

    private boolean isEarlierThanFocus(TaskDetail focusTask, TaskDetail candidate) {
        if (focusTask.getCreateTime() == null || candidate.getCreateTime() == null) {
            return false;
        }
        return !candidate.getCreateTime().isAfter(focusTask.getCreateTime());
    }

    private boolean isFinished(TaskDetail task) {
        return "FINISHED".equalsIgnoreCase(task.getTaskState()) || task.getFinishTime() != null;
    }

    private String inferRelationType(TaskDetail focusTask, TaskDetail candidate) {
        if (sameValue(focusTask.getContainerCode(), candidate.getContainerCode())) {
            return "same_container";
        }
        if (sameValue(extractPrimaryDeviceCode(focusTask), extractPrimaryDeviceCode(candidate))) {
            return "same_device";
        }
        if (sameValue(focusTask.getBusinessTo(), candidate.getBusinessTo()) || sameValue(focusTask.getDefiniteTo(), candidate.getDefiniteTo())) {
            return "same_target_point";
        }
        if (sameValue(focusTask.getBusinessFrom(), candidate.getBusinessFrom()) || sameValue(focusTask.getDefiniteFrom(), candidate.getDefiniteFrom())) {
            return "same_source_point";
        }
        return "time_window_related";
    }

    private String buildRelationReason(TaskDetail focusTask, TaskDetail candidate, String relationType) {
        return switch (relationType) {
            case "same_container" -> "共享容器 " + candidate.getContainerCode();
            case "same_device" -> "共享设备 " + extractPrimaryDeviceCode(candidate);
            case "same_target_point" -> "共享目标点位 " + firstNonBlank(candidate.getBusinessTo(), candidate.getDefiniteTo());
            case "same_source_point" -> "共享起点位 " + firstNonBlank(candidate.getBusinessFrom(), candidate.getDefiniteFrom());
            default -> "同时间窗内候选关联任务";
        };
    }

    private String inferBlockageType(TaskDetail focusTask, TaskDetail candidate) {
        if (hasExplicitAbnormal(candidate)) {
            return "SELF_ERROR";
        }
        if (sameValue(extractPrimaryDeviceCode(focusTask), extractPrimaryDeviceCode(candidate))) {
            return "RESOURCE_BUSY";
        }
        if (sameValue(focusTask.getBusinessTo(), candidate.getBusinessTo()) || sameValue(focusTask.getDefiniteTo(), candidate.getDefiniteTo())) {
            return "POINT_OCCUPIED";
        }
        if (sameValue(focusTask.getContainerCode(), candidate.getContainerCode())) {
            return "CONTAINER_LOCKED";
        }
        if (hasStrongRelation(focusTask, candidate)) {
            return "UPSTREAM_BLOCKED";
        }
        return "UNKNOWN";
    }

    private String buildBlockageReason(TaskDetail focusTask, TaskDetail candidate, String blockageType) {
        return switch (blockageType) {
            case "SELF_ERROR" -> "候选任务自身存在明确异常或失败状态，可能是当前阻塞链的起点。";
            case "RESOURCE_BUSY" -> "候选任务与当前任务共享设备 " + extractPrimaryDeviceCode(candidate) + "，可能因设备繁忙导致等待。";
            case "POINT_OCCUPIED" -> "候选任务与当前任务共享目标点位 " + firstNonBlank(candidate.getBusinessTo(), candidate.getDefiniteTo()) + "，可能存在点位占用未释放。";
            case "CONTAINER_LOCKED" -> "候选任务与当前任务共享容器 " + candidate.getContainerCode() + "，可能存在容器链路未释放。";
            case "UPSTREAM_BLOCKED" -> "候选任务与当前任务存在强关联，可能处于上游阻塞链。";
            default -> "当前仅识别为时间窗内关联任务，阻塞类型仍待进一步确认。";
        };
    }

    private String buildDirectBlockDescription(TaskDetail focusTask, TaskDetail directBlocker) {
        String blockageType = inferBlockageType(focusTask, directBlocker);
        return "候选直接阻塞关系: " + buildBlockageReason(focusTask, directBlocker, blockageType);
    }

    private String buildRootBlockDescription(TaskDetail rootBlocker, TaskDetail directBlocker) {
        return "候选根阻塞传播关系: 上游任务 " + rootBlocker.getTaskId() + " 可能通过任务 "
                + directBlocker.getTaskId() + " 向下游传播阻塞。";
    }

    private String relationResourceKey(TaskDetail focusTask, TaskDetail candidate) {
        String relationType = inferRelationType(focusTask, candidate);
        return switch (relationType) {
            case "same_container" -> focusTask.getContainerCode();
            case "same_device" -> extractPrimaryDeviceCode(focusTask);
            case "same_target_point" -> firstNonBlank(focusTask.getBusinessTo(), focusTask.getDefiniteTo());
            case "same_source_point" -> firstNonBlank(focusTask.getBusinessFrom(), focusTask.getDefiniteFrom());
            default -> focusTask.getTaskId();
        };
    }

    private String extractPrimaryDeviceCode(TaskDetail task) {
        if (task == null) {
            return null;
        }
        if (task.getTickets() != null) {
            for (TaskDetail.TicketDetail ticket : task.getTickets()) {
                if (StringUtils.hasText(ticket.getDeviceCode())) {
                    return ticket.getDeviceCode();
                }
            }
        }
        if (task.getTaskItems() != null) {
            for (TaskDetail.TaskItemDetail item : task.getTaskItems()) {
                if (StringUtils.hasText(item.getDeviceCode())) {
                    return item.getDeviceCode();
                }
            }
        }
        return null;
    }

    private boolean sameValue(String left, String right) {
        return StringUtils.hasText(left) && left.equals(right);
    }

    private void addIfHasText(Set<String> values, String value) {
        if (StringUtils.hasText(value)) {
            values.add(value);
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }
}
