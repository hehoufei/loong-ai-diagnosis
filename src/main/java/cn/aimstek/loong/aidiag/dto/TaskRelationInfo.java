package cn.aimstek.loong.aidiag.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务依赖关系信息：封装 loong-platform 中任务的显式依赖关系。
 * 用于阻塞分析、上下游追踪、根因定位等场景。
 */
@Data
public class TaskRelationInfo {
    /** 开始依赖的父任务号 */
    private String preStartTaskNo;
    /** 结束依赖的父任务号 */
    private String preEndTaskNo;
    /** 父任务号 */
    private String parentTaskNo;
    /** 根任务号 */
    private String rootTaskNo;
    /** 任务组编码 */
    private String groupCode;
    /** 子任务前序依赖列表 */
    private List<String> preTaskItemNos = new ArrayList<>();
}
