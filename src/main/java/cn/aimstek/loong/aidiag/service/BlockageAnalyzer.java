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

/**
 * 阻塞分析器：基于 loong-platform 的显式依赖关系（preStartTaskNo / preEndTaskNo /
 * parentTaskNo / groupCode）优先识别阻塞，辅以隐式资源占用推断（设备/终点 node/任务组）。
 */
@Component
@RequiredArgsConstructor
public class BlockageAnalyzer {

    /** 任务"已完成"状态集合（不再阻塞下游） */
    private static final Set<String> FINISHED_STATES = Set.of("SUCCESS", "MANUAL_SUCCESS", "CANCEL");

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
                    TaskRelationEdge.UPSTREAM_BLOCKED,
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
        summary.setTaskNo(candidate.getTaskNo());
        summary.setTaskState(candidate.getTaskState());
        summary.setContainerCode(candidate.getContainerCode());
        summary.setStartNode(candidate.getStartNode());
        summary.setEndNode(candidate.getEndNode());
        summary.setDeviceCode(extractPrimaryDeviceCode(candidate));
        summary.setRelationType(relationType);
        summary.setRelationReason(buildRelationReason(focusTask, candidate, relationType));
        summary.setBlockageType(blockageType);
        summary.setBlockageReason(buildBlockageReason(focusTask, candidate, blockageType));
        summary.setErrorMessage(candidate.getErrorMessage());
        return summary;
    }

    /**
     * 直接阻塞者识别策略：
     * 1. 显式依赖优先：focusTask.preStartTaskNo / preEndTaskNo / parentTaskNo 指向且未完成的任务
     * 2. 资源推断兜底：评分最高的强关联未完成任务
     */
    private TaskDetail selectDirectBlocker(TaskDetail focusTask, List<TaskDetail> candidates) {
        // 第一优先：显式 pre 依赖（preStartTaskNo / preEndTaskNo）
        TaskDetail explicitPre = findByTaskNo(candidates, focusTask.getPreStartTaskNo());
        if (explicitPre == null) {
            explicitPre = findByTaskNo(candidates, focusTask.getPreEndTaskNo());
        }
        if (explicitPre != null && !isFinished(explicitPre)) {
            return explicitPre;
        }

        // 第二优先：父任务异常
        TaskDetail parent = findByTaskNo(candidates, focusTask.getParentTaskNo());
        if (parent != null && !isFinished(parent) && hasExplicitAbnormal(parent)) {
            return parent;
        }

        // 第三优先：资源推断（高分优先）
        return candidates.stream()
                .filter(candidate -> !sameTask(focusTask, candidate))
                .filter(candidate -> isEarlierThanFocus(focusTask, candidate))
                .filter(candidate -> !isFinished(candidate))
                .filter(candidate -> hasStrongRelation(focusTask, candidate))
                .max(Comparator.comparingInt(candidate -> directBlockerScore(focusTask, candidate)))
                .orElse(null);
    }

    private TaskDetail selectRootBlocker(List<TaskDetail> candidates, TaskDetail directBlocker) {
        if (candidates.isEmpty()) {
            return directBlocker;
        }
        return candidates.stream()
                .filter(candidate -> !isFinished(candidate))
                .filter(this::hasExplicitAbnormal)
                .min(Comparator.comparingInt(this::rootBlockerScore)
                        .thenComparing(TaskDetail::getCreateTime, Comparator.nullsLast(Comparator.naturalOrder())))
                .orElse(directBlocker);
    }

    private List<String> findImpactedTasks(TaskDetail focusTask, List<TaskDetail> candidates) {
        List<String> impacted = new ArrayList<>();
        for (TaskDetail candidate : candidates) {
            if (sameTask(focusTask, candidate)) {
                continue;
            }
            // 反向显式依赖：候选任务的 preStartTaskNo/preEndTaskNo 指向焦点任务，意味着候选受焦点阻塞
            if (sameValue(candidate.getPreStartTaskNo(), focusTask.getTaskNo())
                    || sameValue(candidate.getPreEndTaskNo(), focusTask.getTaskNo())
                    || sameValue(candidate.getParentTaskNo(), focusTask.getTaskNo())) {
                impacted.add(candidate.getTaskId());
                continue;
            }
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

        // 设备瓶颈
        String deviceCode = extractPrimaryDeviceCode(focusTask);
        if (StringUtils.hasText(deviceCode)) {
            int impacted = (int) candidates.stream()
                    .filter(candidate -> !sameTask(focusTask, candidate))
                    .filter(candidate -> deviceCode.equals(extractPrimaryDeviceCode(candidate)))
                    .count();
            if (impacted > 0) {
                bottlenecks.add(new ResourceBottleneck(ResourceBottleneck.TYPE_DEVICE, deviceCode,
                        "同设备存在关联任务，可能存在设备繁忙或故障", impacted));
            }
        }

        // 节点（点位 → node）瓶颈
        Set<String> nodeKeys = new LinkedHashSet<>();
        addIfHasText(nodeKeys, focusTask.getStartNode());
        addIfHasText(nodeKeys, focusTask.getEndNode());
        addIfHasText(nodeKeys, focusTask.getGoodsLocation());
        for (String nodeKey : nodeKeys) {
            int impacted = (int) candidates.stream()
                    .filter(candidate -> !sameTask(focusTask, candidate))
                    .filter(candidate -> nodeKey.equals(candidate.getStartNode())
                            || nodeKey.equals(candidate.getEndNode())
                            || nodeKey.equals(candidate.getGoodsLocation()))
                    .count();
            if (impacted > 0) {
                bottlenecks.add(new ResourceBottleneck(ResourceBottleneck.TYPE_NODE, nodeKey,
                        "同节点存在排队或占用候选，可能存在节点未释放", impacted));
            }
        }

        // 任务组瓶颈
        String groupCode = focusTask.getGroupCode();
        if (StringUtils.hasText(groupCode)) {
            int impacted = (int) candidates.stream()
                    .filter(candidate -> !sameTask(focusTask, candidate))
                    .filter(candidate -> groupCode.equals(candidate.getGroupCode()))
                    .count();
            if (impacted > 0) {
                bottlenecks.add(new ResourceBottleneck(ResourceBottleneck.TYPE_GROUP, groupCode,
                        "同任务组关联任务存在阻塞或排队", impacted));
            }
        }

        return bottlenecks;
    }

    /**
     * 直接阻塞评分（高分优先）：
     * 显式依赖关系(preStartTaskNo/preEndTaskNo/parentTaskNo 指向): +60
     * 同设备(deviceCode 相同): +40
     * 同终点 node(endNode 相同): +30
     * 同任务组(groupCode 相同): +20
     * 异常/失败状态(SUCCESS/CANCEL/MANUAL_SUCCESS 之外的活跃状态有异常): +10
     */
    private int directBlockerScore(TaskDetail focusTask, TaskDetail candidate) {
        int score = 0;
        if (isExplicitDependency(focusTask, candidate)) {
            score += 60;
        }
        if (sameValue(extractPrimaryDeviceCode(focusTask), extractPrimaryDeviceCode(candidate))) {
            score += 40;
        }
        if (sameValue(focusTask.getEndNode(), candidate.getEndNode())) {
            score += 30;
        }
        if (sameValue(focusTask.getGroupCode(), candidate.getGroupCode())) {
            score += 20;
        }
        if (hasExplicitAbnormal(candidate)) {
            score += 10;
        }
        return score;
    }

    private int rootBlockerScore(TaskDetail candidate) {
        int score = 100;
        if (hasFailedCommand(candidate)) {
            score -= 50;
        }
        if (hasExplicitAbnormal(candidate)) {
            score -= 30;
        }
        if (!isFinished(candidate)) {
            score -= 20;
        }
        if ("Y".equalsIgnoreCase(candidate.getPaused())) {
            score -= 10;
        }
        return score;
    }

    /** 判断候选是否属于焦点任务的显式依赖（被依赖方） */
    private boolean isExplicitDependency(TaskDetail focusTask, TaskDetail candidate) {
        if (focusTask == null || candidate == null) {
            return false;
        }
        String candNo = candidate.getTaskNo();
        if (!StringUtils.hasText(candNo)) {
            return false;
        }
        return candNo.equals(focusTask.getPreStartTaskNo())
                || candNo.equals(focusTask.getPreEndTaskNo())
                || candNo.equals(focusTask.getParentTaskNo());
    }

    private boolean hasExplicitAbnormal(TaskDetail candidate) {
        if (candidate == null) {
            return false;
        }
        if (StringUtils.hasText(candidate.getErrorMessage())) {
            return true;
        }
        if ("Y".equalsIgnoreCase(candidate.getPaused())) {
            return true;
        }
        return hasFailedCommand(candidate);
    }

    /** 候选任务是否存在 FAILED 指令 */
    private boolean hasFailedCommand(TaskDetail candidate) {
        if (candidate == null || candidate.getCommands() == null) {
            return false;
        }
        for (TaskDetail.CommandDetail command : candidate.getCommands()) {
            if ("FAILED".equalsIgnoreCase(command.getCommandState())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 强关系判定：显式依赖 OR 同设备 OR 同起点 node OR 同终点 node OR 同任务组
     */
    private boolean hasStrongRelation(TaskDetail focusTask, TaskDetail candidate) {
        return isExplicitDependency(focusTask, candidate)
                || isExplicitDependency(candidate, focusTask)
                || sameValue(focusTask.getStartNode(), candidate.getStartNode())
                || sameValue(focusTask.getEndNode(), candidate.getEndNode())
                || sameValue(extractPrimaryDeviceCode(focusTask), extractPrimaryDeviceCode(candidate))
                || sameValue(focusTask.getGroupCode(), candidate.getGroupCode());
    }

    private boolean isEarlierThanFocus(TaskDetail focusTask, TaskDetail candidate) {
        if (focusTask.getCreateTime() == null || candidate.getCreateTime() == null) {
            return false;
        }
        return !candidate.getCreateTime().isAfter(focusTask.getCreateTime());
    }

    /** 任务"已完成"判断：状态为 SUCCESS / MANUAL_SUCCESS / CANCEL */
    private boolean isFinished(TaskDetail task) {
        if (task == null) {
            return true;
        }
        String state = task.getTaskState();
        if (StringUtils.hasText(state) && FINISHED_STATES.contains(state.toUpperCase())) {
            return true;
        }
        return task.getFinishTime() != null;
    }

    private String inferRelationType(TaskDetail focusTask, TaskDetail candidate) {
        // 显式依赖优先识别
        if (StringUtils.hasText(candidate.getTaskNo())) {
            if (candidate.getTaskNo().equals(focusTask.getPreStartTaskNo())) {
                return TaskRelationEdge.PRE_START_DEPENDENCY;
            }
            if (candidate.getTaskNo().equals(focusTask.getPreEndTaskNo())) {
                return TaskRelationEdge.PRE_END_DEPENDENCY;
            }
            if (candidate.getTaskNo().equals(focusTask.getParentTaskNo())
                    || (StringUtils.hasText(focusTask.getTaskNo())
                        && focusTask.getTaskNo().equals(candidate.getParentTaskNo()))) {
                return TaskRelationEdge.PARENT_CHILD;
            }
        }
        if (sameValue(extractPrimaryDeviceCode(focusTask), extractPrimaryDeviceCode(candidate))) {
            return TaskRelationEdge.SAME_DEVICE;
        }
        if (sameValue(focusTask.getEndNode(), candidate.getEndNode())) {
            return TaskRelationEdge.SAME_TARGET_POINT;
        }
        if (sameValue(focusTask.getStartNode(), candidate.getStartNode())) {
            return TaskRelationEdge.SAME_SOURCE_POINT;
        }
        if (sameValue(focusTask.getGroupCode(), candidate.getGroupCode())) {
            return TaskRelationEdge.SAME_GROUP;
        }
        return TaskRelationEdge.TIME_WINDOW_RELATED;
    }

    private String buildRelationReason(TaskDetail focusTask, TaskDetail candidate, String relationType) {
        return switch (relationType) {
            case TaskRelationEdge.PRE_START_DEPENDENCY ->
                    "焦点任务的开始依赖任务 " + candidate.getTaskNo() + "（preStartTaskNo）";
            case TaskRelationEdge.PRE_END_DEPENDENCY ->
                    "焦点任务的结束依赖任务 " + candidate.getTaskNo() + "（preEndTaskNo）";
            case TaskRelationEdge.PARENT_CHILD ->
                    "父子任务关系：parentTaskNo=" + firstNonBlank(focusTask.getParentTaskNo(), candidate.getParentTaskNo());
            case TaskRelationEdge.SAME_DEVICE ->
                    "共享设备 " + extractPrimaryDeviceCode(candidate);
            case TaskRelationEdge.SAME_TARGET_POINT ->
                    "共享终点节点 " + candidate.getEndNode();
            case TaskRelationEdge.SAME_SOURCE_POINT ->
                    "共享起点节点 " + candidate.getStartNode();
            case TaskRelationEdge.SAME_GROUP ->
                    "同任务组 " + candidate.getGroupCode();
            default -> "同时间窗内候选关联任务";
        };
    }

    /**
     * 阻塞类型推断：显式依赖未完成 → DEPENDENCY_BLOCKED；
     * 焦点任务被暂停 → PAUSED；候选有 FAILED 指令 → COMMAND_FAILED；
     * 自身异常 → SELF_ERROR；同设备 → RESOURCE_BUSY；同终点 → POINT_OCCUPIED；
     * 强关联 → UPSTREAM_BLOCKED；其余 → UNKNOWN。
     */
    private String inferBlockageType(TaskDetail focusTask, TaskDetail candidate) {
        if (isExplicitDependency(focusTask, candidate) && !isFinished(candidate)) {
            return "DEPENDENCY_BLOCKED";
        }
        if ("Y".equalsIgnoreCase(focusTask.getPaused())) {
            return "PAUSED";
        }
        if (hasFailedCommand(candidate)) {
            return "COMMAND_FAILED";
        }
        if (hasExplicitAbnormal(candidate)) {
            return "SELF_ERROR";
        }
        if (sameValue(extractPrimaryDeviceCode(focusTask), extractPrimaryDeviceCode(candidate))) {
            return "RESOURCE_BUSY";
        }
        if (sameValue(focusTask.getEndNode(), candidate.getEndNode())) {
            return "POINT_OCCUPIED";
        }
        if (hasStrongRelation(focusTask, candidate)) {
            return "UPSTREAM_BLOCKED";
        }
        return "UNKNOWN";
    }

    private String buildBlockageReason(TaskDetail focusTask, TaskDetail candidate, String blockageType) {
        return switch (blockageType) {
            case "DEPENDENCY_BLOCKED" -> "焦点任务通过 preStartTaskNo/preEndTaskNo/parentTaskNo 显式依赖任务 "
                    + candidate.getTaskNo() + "，但其状态 " + candidate.getTaskState() + " 尚未完成。";
            case "PAUSED" -> "焦点任务被人工暂停（paused=Y），需要先恢复后再继续。";
            case "COMMAND_FAILED" -> "候选任务存在 FAILED 状态指令（commandState=FAILED），需要先处理失败指令。";
            case "SELF_ERROR" -> "候选任务自身存在明确异常（错误消息或暂停标记），可能是当前阻塞链的起点。";
            case "RESOURCE_BUSY" -> "候选任务与当前任务共享设备 " + extractPrimaryDeviceCode(candidate)
                    + "，可能因设备繁忙导致等待。";
            case "POINT_OCCUPIED" -> "候选任务与当前任务共享终点节点 " + candidate.getEndNode()
                    + "，可能存在节点占用未释放。";
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
            case TaskRelationEdge.PRE_START_DEPENDENCY -> focusTask.getPreStartTaskNo();
            case TaskRelationEdge.PRE_END_DEPENDENCY -> focusTask.getPreEndTaskNo();
            case TaskRelationEdge.PARENT_CHILD -> firstNonBlank(focusTask.getParentTaskNo(), candidate.getParentTaskNo());
            case TaskRelationEdge.SAME_DEVICE -> extractPrimaryDeviceCode(focusTask);
            case TaskRelationEdge.SAME_TARGET_POINT -> focusTask.getEndNode();
            case TaskRelationEdge.SAME_SOURCE_POINT -> focusTask.getStartNode();
            case TaskRelationEdge.SAME_GROUP -> focusTask.getGroupCode();
            default -> focusTask.getTaskId();
        };
    }

    private String extractPrimaryDeviceCode(TaskDetail task) {
        if (task == null) {
            return null;
        }
        // 优先返回当前货物所在设备
        if (StringUtils.hasText(task.getGoodsDeviceCode())) {
            return task.getGoodsDeviceCode();
        }
        if (task.getCommands() != null) {
            for (TaskDetail.CommandDetail command : task.getCommands()) {
                if (StringUtils.hasText(command.getDeviceCode())) {
                    return command.getDeviceCode();
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

    private TaskDetail findByTaskNo(List<TaskDetail> candidates, String taskNo) {
        if (!StringUtils.hasText(taskNo) || candidates == null) {
            return null;
        }
        for (TaskDetail candidate : candidates) {
            if (taskNo.equals(candidate.getTaskNo())) {
                return candidate;
            }
        }
        return null;
    }

    private boolean sameTask(TaskDetail left, TaskDetail right) {
        if (left == null || right == null) {
            return false;
        }
        return Objects.equals(left.getTaskId(), right.getTaskId());
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
