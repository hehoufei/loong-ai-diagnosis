package cn.aimstek.loong.aidiag.client;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 枚举反向映射器：将平台返回的中文枚举值映射为标准 code。
 * 如果输入已经是 code，则原样返回。
 */
@Component
public class EnumReverseMapper {

    // 任务状态反向映射表（中文 → code）
    private static final Map<String, String> TASK_STATE_REVERSE = Map.of(
            "等待拆分", "WAIT_SPLIT",
            "等待规划", "WAIT_PLAN",
            "运行中", "RUNNING",
            "完成", "SUCCESS",
            "手工完成", "MANUAL_SUCCESS",
            "取消", "CANCEL",
            "暂停", "PAUSED"
    );

    // 子任务状态反向映射表
    private static final Map<String, String> TASK_ITEM_STATE_REVERSE = Map.of(
            "等待拆分", "WAIT_SPLIT",
            "等待规划", "WAIT_PLAN",
            "运行中", "RUNNING",
            "完成", "SUCCESS",
            "手工完成", "MANUAL_SUCCESS",
            "取消", "CANCEL",
            "暂停", "PAUSED"
    );

    // 指令状态反向映射表
    private static final Map<String, String> COMMAND_STATE_REVERSE = Map.of(
            "等待", "WAIT",
            "已发送", "SENT",
            "已确认", "ACKED",
            "成功", "SUCCESS",
            "失败", "FAILED",
            "超时", "TIMEOUT"
    );

    // 暂停状态映射
    private static final Map<String, String> PAUSED_REVERSE = Map.of(
            "是", "YES",
            "否", "NO"
    );

    // 调度通道映射
    private static final Map<String, String> SCHEDULE_CHANNEL_REVERSE = Map.of(
            "引擎", "ENGINE",
            "业务", "BIZ",
            "系统", "SYSTEM"
    );

    // 拆分完成状态映射
    private static final Map<String, String> SPLIT_FINISH_REVERSE = Map.of(
            "已完成", "FINISH",
            "未完成", "NOT_FINISH"
    );

    /**
     * 转换任务状态：中文 → code，已是 code 时原样返回。
     */
    public String toTaskStateCode(String chineseOrCode) {
        if (chineseOrCode == null || chineseOrCode.isEmpty()) {
            return chineseOrCode;
        }
        return TASK_STATE_REVERSE.getOrDefault(chineseOrCode, chineseOrCode);
    }

    /**
     * 转换子任务状态：中文 → code，已是 code 时原样返回。
     */
    public String toTaskItemStateCode(String chineseOrCode) {
        if (chineseOrCode == null || chineseOrCode.isEmpty()) {
            return chineseOrCode;
        }
        return TASK_ITEM_STATE_REVERSE.getOrDefault(chineseOrCode, chineseOrCode);
    }

    /**
     * 转换指令状态：中文 → code，已是 code 时原样返回。
     */
    public String toCommandStateCode(String chineseOrCode) {
        if (chineseOrCode == null || chineseOrCode.isEmpty()) {
            return chineseOrCode;
        }
        return COMMAND_STATE_REVERSE.getOrDefault(chineseOrCode, chineseOrCode);
    }

    /**
     * 转换暂停状态：中文 → code，已是 code 时原样返回。
     */
    public String toPausedCode(String chineseOrCode) {
        if (chineseOrCode == null || chineseOrCode.isEmpty()) {
            return chineseOrCode;
        }
        return PAUSED_REVERSE.getOrDefault(chineseOrCode, chineseOrCode);
    }

    /**
     * 转换调度通道：中文 → code，已是 code 时原样返回。
     */
    public String toScheduleChannelCode(String chineseOrCode) {
        if (chineseOrCode == null || chineseOrCode.isEmpty()) {
            return chineseOrCode;
        }
        return SCHEDULE_CHANNEL_REVERSE.getOrDefault(chineseOrCode, chineseOrCode);
    }

    /**
     * 转换拆分完成状态：中文 → code，已是 code 时原样返回。
     */
    public String toSplitFinishCode(String chineseOrCode) {
        if (chineseOrCode == null || chineseOrCode.isEmpty()) {
            return chineseOrCode;
        }
        return SPLIT_FINISH_REVERSE.getOrDefault(chineseOrCode, chineseOrCode);
    }
}
