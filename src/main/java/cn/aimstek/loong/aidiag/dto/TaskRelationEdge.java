package cn.aimstek.loong.aidiag.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskRelationEdge {
    private String fromTaskId;
    private String toTaskId;
    private String relationType;
    private String resourceKey;
    private String description;

    // ===== 显式依赖关系类型 =====
    /** preStartTaskNo 指向，开始依赖 */
    public static final String PRE_START_DEPENDENCY = "pre_start_dependency";
    /** preEndTaskNo 指向，结束依赖 */
    public static final String PRE_END_DEPENDENCY = "pre_end_dependency";
    /** 父子任务关系（parentTaskNo 指向） */
    public static final String PARENT_CHILD = "parent_child";
    /** 同任务组（groupCode 相同） */
    public static final String SAME_GROUP = "same_group";
    /** 子任务前序依赖（preTaskItemNo 指向） */
    public static final String PRE_TASK_ITEM = "pre_task_item";

    // ===== 隐式资源关系类型 =====
    /** 共享设备 */
    public static final String SAME_DEVICE = "same_device";
    /** 共享终点 node */
    public static final String SAME_TARGET_POINT = "same_target_point";
    /** 共享起点 node */
    public static final String SAME_SOURCE_POINT = "same_source_point";

    // ===== 阻塞链推导关系 =====
    /** 上游阻塞链路 */
    public static final String UPSTREAM_BLOCKED = "upstream_blocked";
    /** 时间窗内候选 */
    public static final String TIME_WINDOW_RELATED = "time_window_related";
}
