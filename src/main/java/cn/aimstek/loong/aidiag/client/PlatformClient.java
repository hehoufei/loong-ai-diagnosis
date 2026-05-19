package cn.aimstek.loong.aidiag.client;

import cn.aimstek.loong.aidiag.client.dto.*;
import lombok.Data;

import java.util.List;

/**
 * 平台数据访问接口。
 */
public interface PlatformClient {

    /**
     * 查询大任务信息。
     * @param taskNo 任务号
     * @return 大任务详情
     * @throws cn.aimstek.loong.aidiag.exception.AiDiagnosisException 任务不存在时抛 TASK_NOT_FOUND
     */
    PlatformTask queryTask(String taskNo);

    /**
     * 查询子任务 + 指令 + 依赖关系。
     * @param taskNo 任务号
     * @return 子任务包（含 items、relations）
     */
    TaskItemBundle queryTaskItemBundle(String taskNo);

    /**
     * 查询任务组信息（可选，失败不阻断）。
     * @param groupCode 任务组编码
     * @return 任务组信息，失败时返回 null
     */
    PlatformTaskGroup queryTaskGroup(String groupCode);

    /**
     * 查询设备状态（可选，失败不阻断）。
     * @param deviceCode 设备编码
     * @return 设备状态，失败时返回 null
     */
    PlatformDeviceStatus queryDevice(String deviceCode);

    /**
     * 子任务包：包含子任务列表和依赖关系。
     */
    @Data
    class TaskItemBundle {
        private List<PlatformTaskItem> items;
        private List<PlatformTaskRelation> relations;
    }
}
