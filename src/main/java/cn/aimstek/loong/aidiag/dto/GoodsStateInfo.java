package cn.aimstek.loong.aidiag.dto;

import lombok.Data;

/**
 * 货物实时位置 DTO，对应 loong-platform 表 sc_goods_state。
 * 用于诊断货物在运输过程中的卡滞、遗落等问题。
 */
@Data
public class GoodsStateInfo {

    /** 货物编号 */
    private String goodsCode;

    /** 容器编号 */
    private String containerCode;

    /** 位置编码 */
    private String locationCode;

    /** 当前任务号 */
    private String currentTaskNo;

    /** 当前子任务号 */
    private String currentTaskItemNo;

    /** 当前节点位置 */
    private String currentNode;

    /** 货物状态 */
    private String state;
}
