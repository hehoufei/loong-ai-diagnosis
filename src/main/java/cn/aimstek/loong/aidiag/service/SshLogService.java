package cn.aimstek.loong.aidiag.service;

import cn.aimstek.loong.aidiag.config.EnvConfig;
import cn.aimstek.loong.aidiag.dto.PlatformLogResult;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 通过 SSH 连接远程服务器查询 loong-platform 日志。
 *
 * <p>能力概述：
 * <ul>
 *   <li>同时扫描主日志（如 {@code schedule_loong-platform_all.log}）以及 {@code archived/}
 *       目录下覆盖时间窗的滚动归档文件，避免跨日或重启导致的漏查。</li>
 *   <li>使用 awk 字符串区间比较 {@code $0>=s && $0<=e} 过滤时间，
 *       不要求时间戳在日志中"逐字"出现；保留续行（堆栈）。</li>
 *   <li>支持多关键词 OR 过滤（taskNo / taskItemNo / commandNo / deviceCode 等）。</li>
 *   <li>使用 {@code head -N}（按时间正序首段）截断，避免丢失早期日志。</li>
 *   <li>结果包装为 {@link PlatformLogResult}，无命中 / 缺凭据 / 报错都能区分，方便前端展示。</li>
 * </ul>
 */
@Slf4j
@Service
public class SshLogService {

    @Autowired
    private EnvConfig envConfig;

    private static final String DEFAULT_LOG_PATH = "/aims/loong/loong-main/logs/schedule_loong-platform_all.log";
    private static final int MAX_LINES = 2000;
    private static final int SESSION_TIMEOUT = 10_000;   // 10秒连接超时
    private static final int CHANNEL_TIMEOUT = 30_000;   // 30秒命令执行超时

    /** 兼容旧接口：不带 extras，仅返回行列表。 */
    public List<String> queryLogs(String taskNo, String startTime, String endTime, String env) {
        return queryLogs(taskNo, Collections.emptyList(), startTime, endTime, env);
    }

    /** 兼容旧接口：带 extras，仅返回行列表。 */
    public List<String> queryLogs(String taskNo, Collection<String> extraKeywords,
                                   String startTime, String endTime, String env) {
        return queryLogsDetailed(taskNo, extraKeywords, startTime, endTime, env).getLines();
    }

    /**
     * 详细版：返回包含状态、原因、命令片段的结果对象。
     */
    public PlatformLogResult queryLogsDetailed(String taskNo, Collection<String> extraKeywords,
                                                String startTime, String endTime, String env) {
        EnvConfig.EnvItem envItem = (env != null && !env.isEmpty())
                ? envConfig.getEnv(env)
                : envConfig.getActiveEnvItem();
        if (envItem == null) {
            return PlatformLogResult.of(PlatformLogResult.Status.SKIPPED_NO_ENV,
                    "未找到有效环境配置，跳过 SSH 日志查询");
        }

        String host = envItem.getSshHost();
        if (host == null || host.isEmpty()) {
            host = extractHostFromUrl(envItem.getWcsUrl());
        }
        if (host == null || host.isEmpty()) {
            return PlatformLogResult.of(PlatformLogResult.Status.SKIPPED_NO_HOST,
                    "无法从环境配置解析 SSH 主机");
        }

        int port = envItem.getSshPort() != null ? envItem.getSshPort() : 22;
        String username = envItem.getSshUsername();
        String password = envItem.getSshPassword();
        if (username == null || username.isEmpty()) {
            return PlatformLogResult.of(PlatformLogResult.Status.SKIPPED_NO_CRED,
                    "环境未配置 SSH 用户名，跳过远程日志查询");
        }

        String logPath = (envItem.getLogFilePath() == null || envItem.getLogFilePath().isEmpty())
                ? DEFAULT_LOG_PATH
                : envItem.getLogFilePath();

        Set<String> keywords = new LinkedHashSet<>();
        if (taskNo != null && !taskNo.isBlank()) {
            keywords.add(taskNo.trim());
        }
        if (extraKeywords != null) {
            for (String k : extraKeywords) {
                if (k != null && !k.isBlank()) {
                    keywords.add(k.trim());
                }
            }
        }

        boolean hasTimeRange = startTime != null && !startTime.isBlank()
                            && endTime != null && !endTime.isBlank();
        if (keywords.isEmpty() && !hasTimeRange) {
            return PlatformLogResult.of(PlatformLogResult.Status.SKIPPED_NO_FILTER,
                    "未提供关键词或时间窗，跳过远程日志查询");
        }

        String command = buildCommand(keywords, startTime, endTime, logPath);
        log.info("SSH日志查询: host={}, user={}, path={}, kw={}, range=[{} ~ {}]",
                host, username, logPath, keywords, startTime, endTime);

        ExecResult exec = executeCommand(host, port, username, password, command);
        if (exec.error != null) {
            return new PlatformLogResult(PlatformLogResult.Status.ERROR,
                    "SSH 执行失败: " + exec.error, Collections.emptyList(), command);
        }
        if (exec.lines.isEmpty()) {
            String reason = "时间窗与关键词命中 0 行";
            if (exec.stderr != null && !exec.stderr.isBlank()) {
                reason += "；stderr: " + exec.stderr.trim();
            }
            return new PlatformLogResult(PlatformLogResult.Status.EMPTY, reason,
                    Collections.emptyList(), command);
        }
        return PlatformLogResult.ok(exec.lines, command);
    }

    /**
     * 构建远程 shell 命令。
     *
     * <p>骨架：
     * <pre>
     * { find archived ... | sort ; echo main_log ; } | xargs -r cat
     *   | awk -v s=START -v e=END '/^YYYY-MM-DD/ { ir=($0>=s && $0<=e) } ir'
     *   | grep -E '(kw1|kw2|...)'
     *   | head -n MAX
     * </pre>
     */
    private String buildCommand(Set<String> keywords, String startTime, String endTime, String logPath) {
        StringBuilder script = new StringBuilder();
        script.append("LOG=").append(shellQuote(logPath)).append("; ");
        script.append("DIR=$(dirname \"$LOG\"); BASE=$(basename \"$LOG\"); ");
        script.append("{ ");
        if (startTime != null && !startTime.isBlank()) {
            script.append("find \"$DIR/archived\" -maxdepth 2 -type f -name \"$BASE*\" -newermt ")
                  .append(shellQuote(startTime)).append(" 2>/dev/null | sort; ");
        } else {
            script.append("find \"$DIR/archived\" -maxdepth 2 -type f -name \"$BASE*\" 2>/dev/null | sort; ");
        }
        script.append("echo \"$LOG\"; ");
        script.append("} | xargs -r cat 2>/dev/null");

        if (startTime != null && !startTime.isBlank() && endTime != null && !endTime.isBlank()) {
            String awkProg = "/^[0-9]{4}-[0-9]{2}-[0-9]{2}/ { ir=($0>=s && $0<=e) } ir";
            script.append(" | awk -v s=").append(shellQuote(startTime))
                  .append(" -v e=").append(shellQuote(endTime))
                  .append(' ').append(shellQuote(awkProg));
        }

        if (!keywords.isEmpty()) {
            String pattern = keywords.stream()
                    .map(this::escapeRegex)
                    .collect(Collectors.joining("|"));
            script.append(" | grep -E ").append(shellQuote(pattern));
        }

        script.append(" | head -n ").append(MAX_LINES);

        return "bash -c " + shellQuote(script.toString());
    }

    private ExecResult executeCommand(String host, int port, String username, String password, String command) {
        ExecResult result = new ExecResult();
        Session session = null;
        ChannelExec channel = null;
        try {
            JSch jsch = new JSch();
            session = jsch.getSession(username, host, port);
            session.setPassword(password);
            session.setConfig("StrictHostKeyChecking", "no");
            session.setTimeout(SESSION_TIMEOUT);
            session.connect(SESSION_TIMEOUT);

            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(command);
            channel.setInputStream(null);

            ByteArrayOutputStream errStream = new ByteArrayOutputStream();
            channel.setErrStream(errStream);

            InputStream inputStream = channel.getInputStream();
            channel.connect(CHANNEL_TIMEOUT);

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    result.lines.add(line);
                    if (result.lines.size() >= MAX_LINES) break;
                }
            }
            result.stderr = errStream.toString(StandardCharsets.UTF_8);
            log.info("SSH日志查询完成: host={}, lines={}, stderrBytes={}",
                    host, result.lines.size(), errStream.size());
        } catch (Exception e) {
            log.warn("SSH日志查询失败: host={}, error={}", host, e.getMessage(), e);
            result.error = e.getMessage();
        } finally {
            if (channel != null && channel.isConnected()) channel.disconnect();
            if (session != null && session.isConnected()) session.disconnect();
        }
        return result;
    }

    private String extractHostFromUrl(String url) {
        if (url == null || url.isEmpty()) return null;
        try {
            return new java.net.URL(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    /** POSIX 单引号转义：把每个 ' 替换为 '\'' 然后整体用单引号包起来。 */
    private String shellQuote(String s) {
        if (s == null) return "''";
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** 转义 ERE 元字符，使关键词作为字面量参与 grep -E。 */
    private String escapeRegex(String s) {
        return s.replaceAll("([\\\\^$.*+?()\\[\\]{}|])", "\\\\$1");
    }

    private static class ExecResult {
        List<String> lines = new ArrayList<>();
        String stderr;
        String error;
    }
}
