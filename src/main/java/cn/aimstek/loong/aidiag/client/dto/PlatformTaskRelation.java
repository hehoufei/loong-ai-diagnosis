package cn.aimstek.loong.aidiag.client.dto;

import lombok.Data;

/**
 * 子任务依赖关系。
 */
@Data
public class PlatformTaskRelation {

    private String fromTaskItemNo;
    private String toTaskItemNo;
}
