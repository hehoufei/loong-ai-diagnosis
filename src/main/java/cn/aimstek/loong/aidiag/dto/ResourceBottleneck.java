package cn.aimstek.loong.aidiag.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResourceBottleneck {
    private String resourceType;
    private String resourceKey;
    private String description;
    private Integer impactedTaskCount;

    // ===== 资源类型常量 =====
    /** 设备资源 */
    public static final String TYPE_DEVICE = "device";
    /** 节点资源（点位 → node） */
    public static final String TYPE_NODE = "node";
    /** 任务组资源 */
    public static final String TYPE_GROUP = "group";
}
