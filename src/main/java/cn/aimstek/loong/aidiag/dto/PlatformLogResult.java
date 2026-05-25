package cn.aimstek.loong.aidiag.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Collections;
import java.util.List;

/**
 * 远程平台日志查询结果。
 * 不论是否有命中，都返回一个明确的状态码和说明，
 * 便于前端区分「真的没日志」「凭据没配」「执行失败」。
 */
@Data
@AllArgsConstructor
public class PlatformLogResult {

    public enum Status {
        OK,                 // 命中并返回若干行
        EMPTY,              // SSH 执行成功但无命中行
        SKIPPED_NO_ENV,     // 环境未配置 / 找不到激活环境
        SKIPPED_NO_HOST,    // host 解析不出
        SKIPPED_NO_CRED,    // 缺少 SSH 用户名等凭据
        SKIPPED_NO_FILTER,  // 关键词与时间窗都为空，主动跳过
        ERROR               // 连接或命令执行抛异常
    }

    private Status status;
    private String message;
    private List<String> lines;
    private String command;     // 实际下发到远端的 shell 片段（去敏后），便于排错

    public static PlatformLogResult of(Status status, String message) {
        return new PlatformLogResult(status, message, Collections.emptyList(), null);
    }

    public static PlatformLogResult ok(List<String> lines, String command) {
        return new PlatformLogResult(Status.OK, "查询成功", lines, command);
    }

    public static PlatformLogResult empty(String command) {
        return new PlatformLogResult(Status.EMPTY, "时间窗与关键词命中 0 行", Collections.emptyList(), command);
    }

    public boolean hasLines() {
        return lines != null && !lines.isEmpty();
    }
}
