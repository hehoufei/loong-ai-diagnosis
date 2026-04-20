package cn.aimstek.loong.aidiag.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工具结果事件
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ToolResultEvent {

    /** 工具名称 */
    private String tool;

    /** 结果摘要 */
    private String result;
}
