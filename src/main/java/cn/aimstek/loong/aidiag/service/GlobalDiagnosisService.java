package cn.aimstek.loong.aidiag.service;

import cn.aimstek.loong.aidiag.dto.DiagnoseRequest;
import cn.aimstek.loong.aidiag.dto.DiagnoseResponse;
import cn.aimstek.loong.aidiag.dto.ResourceBottleneck;
import cn.aimstek.loong.aidiag.dto.RootCauseItem;
import cn.aimstek.loong.aidiag.dto.TaskRelationSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class GlobalDiagnosisService {

    private final GlobalDiagnosisContextAssembler contextAssembler;
    private final DiagnosisResponseMapper responseMapper;

    public DiagnoseResponse diagnose(DiagnoseRequest request) {
        GlobalDiagnosisContext context = contextAssembler.assemble(request);
        DiagnoseResponse response = new DiagnoseResponse();
        response.setAnalysisScope("global");
        response.setDiagnosisMode(context.getDiagnosisMode());
        response.setConfidence(0.85);
        response.setTraceId(context.getTraceId());
        response.setRelationSnapshot(context.getRelationSnapshot());
        response.setSummary(buildSummary(context.getRelationSnapshot()));
        response.setRootCauses(buildRootCauses(context.getRelationSnapshot()));
        response.setActions(buildActions(context.getRelationSnapshot()));
        return responseMapper.normalize(response);
    }

    private String buildSummary(TaskRelationSnapshot snapshot) {
        TaskRelationSnapshot.TaskSummary directBlocker = findTask(snapshot, snapshot.getDirectBlockerTaskId()).orElse(null);
        TaskRelationSnapshot.TaskSummary rootBlocker = findTask(snapshot, snapshot.getRootBlockerTaskId()).orElse(null);
        ResourceBottleneck primaryBottleneck = snapshot.getBottleneckResources().isEmpty() ? null : snapshot.getBottleneckResources().get(0);

        StringBuilder summary = new StringBuilder();
        summary.append("任务 ").append(snapshot.getFocusTaskId()).append(" 已完成全局关联分析");

        if (directBlocker != null) {
            summary.append("，直接阻塞候选为 ").append(directBlocker.getTaskId())
                    .append("（").append(blockageTypeLabel(directBlocker.getBlockageType())).append("）");
        }
        if (rootBlocker != null) {
            summary.append("，根阻塞候选为 ").append(rootBlocker.getTaskId())
                    .append("（").append(blockageTypeLabel(rootBlocker.getBlockageType())).append("）");
        }
        if (primaryBottleneck != null) {
            summary.append("，首要瓶颈资源为 ").append(primaryBottleneck.getResourceType()).append(":")
                    .append(primaryBottleneck.getResourceKey());
        }
        summary.append("，候选影响任务数 ").append(snapshot.getImpactedTaskIds().size()).append("。");
        return summary.toString();
    }

    private List<RootCauseItem> buildRootCauses(TaskRelationSnapshot snapshot) {
        List<RootCauseItem> items = new ArrayList<>();

        findTask(snapshot, snapshot.getRootBlockerTaskId()).ifPresent(task ->
                items.add(new RootCauseItem("根阻塞候选",
                        "检测到上游候选任务 " + task.getTaskId() + " 可能是链路最早异常点，阻塞类型为"
                                + blockageTypeLabel(task.getBlockageType()) + "。"
                                + suffix(task.getBlockageReason()))));

        findTask(snapshot, snapshot.getDirectBlockerTaskId()).ifPresent(task ->
                items.add(new RootCauseItem("直接阻塞候选",
                        "当前任务可能直接等待任务 " + task.getTaskId() + "。"
                                + suffix(task.getBlockageReason()))));

        for (ResourceBottleneck bottleneck : snapshot.getBottleneckResources()) {
            items.add(new RootCauseItem("资源瓶颈", bottleneck.getDescription() + "，资源=" + bottleneck.getResourceKey()
                    + "，关联任务数=" + bottleneck.getImpactedTaskCount()));
        }
        return items;
    }

    private List<String> buildActions(TaskRelationSnapshot snapshot) {
        List<String> actions = new ArrayList<>();

        findTask(snapshot, snapshot.getRootBlockerTaskId()).ifPresent(task -> {
            actions.add("优先核查根阻塞候选任务 " + task.getTaskId() + "，重点关注"
                    + actionTargetByBlockageType(task.getBlockageType()) + "。");
            if (StringUtils.hasText(task.getBlockageReason())) {
                actions.add("根阻塞候选任务判断依据：" + task.getBlockageReason() + "。");
            }
        });

        findTask(snapshot, snapshot.getDirectBlockerTaskId())
                .filter(task -> !task.getTaskId().equals(snapshot.getRootBlockerTaskId()))
                .ifPresent(task -> actions.add("确认直接阻塞候选任务 " + task.getTaskId() + " 是否已完成"
                        + actionTargetByBlockageType(task.getBlockageType()) + "的释放或恢复。"));

        for (ResourceBottleneck bottleneck : snapshot.getBottleneckResources()) {
            actions.add("排查瓶颈资源 " + bottleneck.getResourceType() + "=" + bottleneck.getResourceKey()
                    + "，检查是否存在" + bottleneckRiskHint(bottleneck.getResourceType()) + "。");
        }
        if (actions.isEmpty()) {
            actions.add("当前未识别出明确阻塞链，请继续补充设备、点位与时间窗关联证据。");
        }
        return actions;
    }

    private Optional<TaskRelationSnapshot.TaskSummary> findTask(TaskRelationSnapshot snapshot, String taskId) {
        if (!StringUtils.hasText(taskId)) {
            return Optional.empty();
        }
        return snapshot.getRelatedTasks().stream()
                .filter(task -> taskId.equals(task.getTaskId()))
                .findFirst();
    }

    private String blockageTypeLabel(String blockageType) {
        if (!StringUtils.hasText(blockageType)) {
            return "待进一步确认";
        }
        return switch (blockageType) {
            case "DEPENDENCY_BLOCKED" -> "显式依赖未完成";
            case "PAUSED" -> "任务已暂停";
            case "COMMAND_FAILED" -> "指令执行失败";
            case "SELF_ERROR" -> "任务自身异常";
            case "RESOURCE_BUSY" -> "资源繁忙";
            case "POINT_OCCUPIED" -> "节点占用";
            case "UPSTREAM_BLOCKED" -> "上游阻塞";
            default -> "待进一步确认";
        };
    }

    private String actionTargetByBlockageType(String blockageType) {
        if (!StringUtils.hasText(blockageType)) {
            return "任务状态与资源占用情况";
        }
        return switch (blockageType) {
            case "DEPENDENCY_BLOCKED" -> "preStartTaskNo / preEndTaskNo / parentTaskNo 指向任务的完成状态";
            case "PAUSED" -> "任务暂停原因与恢复条件";
            case "COMMAND_FAILED" -> "FAILED 状态指令的错误信息与设备反馈";
            case "SELF_ERROR" -> "任务自身报错、异常码和执行状态";
            case "RESOURCE_BUSY" -> "设备状态、排队情况和可用性";
            case "POINT_OCCUPIED" -> "终点节点占用与释放情况";
            case "UPSTREAM_BLOCKED" -> "上游任务执行状态和资源释放情况";
            default -> "任务状态与资源占用情况";
        };
    }

    private String bottleneckRiskHint(String resourceType) {
        if (!StringUtils.hasText(resourceType)) {
            return "占用未释放或调度积压";
        }
        return switch (resourceType) {
            case "device" -> "设备故障、设备繁忙或排队积压";
            case "node" -> "节点占用未释放或路径冲突";
            case "group" -> "同任务组内其他任务阻塞或排队";
            default -> "占用未释放或调度积压";
        };
    }

    private String suffix(String text) {
        return StringUtils.hasText(text) ? text : "";
    }
}
