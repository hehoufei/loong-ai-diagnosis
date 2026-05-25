package cn.aimstek.loong.aidiag.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 任务组成员 DTO，从 sc_task 中按 group_code 聚合查询，
 * 用于诊断同组任务间的依赖与阻塞关系。
 */
@Data
public class TaskGroupMember {

    /** 任务号 */
    private String taskNo;

    /** 任务状态 */
    private String taskState;

    /** 业务类型 */
    private String bizType;

    /** 任务组角色 */
    private String groupRole;

    /** 开始时间 */
    private LocalDateTime startTime;

    /** 完成时间 */
    private LocalDateTime finishTime;
}
