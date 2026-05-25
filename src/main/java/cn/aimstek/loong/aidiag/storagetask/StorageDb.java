package cn.aimstek.loong.aidiag.storagetask;

import cn.aimstek.loong.aidiag.storagetask.dto.StorageTaskConfig;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 单独管理 storage 任务相关的数据库操作 (loong-platform 数据库),
 * 用 DriverManager 短连接, 不与诊断主数据源耦合.
 *
 * 校验 SQL / 任务状态 SQL 都从 StorageTaskConfig 取, 用户可以在 UI 上自定义.
 */
@Slf4j
public class StorageDb {

    public static final String CODES_PLACEHOLDER = "{CODES}";

    private final StorageTaskConfig cfg;

    public StorageDb(StorageTaskConfig cfg) {
        this.cfg = cfg;
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(cfg.getDbUrl(), cfg.getDbUsername(), cfg.getDbPassword());
    }

    /** 测试连接, 返回成功或抛异常 */
    public void ping() throws SQLException {
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement("SELECT 1")) {
            ps.executeQuery();
        }
    }

    /**
     * 在 map_storage_area 表中过滤出真实可用的库位.
     * 使用 cfg.validateLocationSql, 必须包含 {CODES} 占位符,
     * 运行时按批次大小展开成 ?,?,?... 并用 PreparedStatement 绑定.
     */
    public List<String> filterExistingCodes(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return Collections.emptyList();
        }
        String template = cfg.getValidateLocationSql();
        validateSelectSql("validateLocationSql", template);
        if (!template.contains(CODES_PLACEHOLDER)) {
            throw new IllegalArgumentException(
                    "validateLocationSql 必须包含占位符 " + CODES_PLACEHOLDER);
        }

        Set<String> existing = new HashSet<>();
        int batch = 500;

        try (Connection conn = open()) {
            for (int i = 0; i < codes.size(); i += batch) {
                List<String> sub = codes.subList(i, Math.min(i + batch, codes.size()));
                String placeholders = buildPlaceholders(sub.size());
                String sql = template.replace(CODES_PLACEHOLDER, placeholders);

                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    for (int j = 0; j < sub.size(); j++) {
                        ps.setString(j + 1, sub.get(j));
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            existing.add(rs.getString(1));
                        }
                    }
                }
            }
        } catch (SQLException e) {
            log.error("查询 map_storage_area 失败: {}", e.getMessage(), e);
            throw new RuntimeException("查询 map_storage_area 失败: " + e.getMessage(), e);
        }

        // 保留原始顺序
        List<String> result = new ArrayList<>(codes.size());
        for (String c : codes) {
            if (existing.contains(c)) {
                result.add(c);
            }
        }
        return result;
    }

    /** 查询任务的当前 task_state, 找不到返回 null */
    public String queryTaskState(String taskNo) {
        String sql = cfg.getQueryTaskStateSql();
        try {
            validateSelectSql("queryTaskStateSql", sql);
        } catch (IllegalArgumentException e) {
            log.warn("queryTaskStateSql 不合法: {}", e.getMessage());
            return null;
        }
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            // 仅支持 1 个 ? 占位符
            int paramCount = countQuestionMarks(sql);
            if (paramCount >= 1) {
                ps.setString(1, taskNo);
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString(1);
                }
            }
        } catch (SQLException e) {
            log.warn("查询 sc_task 状态失败 taskNo={}: {}", taskNo, e.getMessage());
        }
        return null;
    }

    /** 仅允许 SELECT 语句, 防止 UI 误填 update/delete */
    public static void validateSelectSql(String name, String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        String trimmed = sql.trim();
        // 跳过开头的 -- 注释行
        while (trimmed.startsWith("--")) {
            int nl = trimmed.indexOf('\n');
            if (nl < 0) { trimmed = ""; break; }
            trimmed = trimmed.substring(nl + 1).trim();
        }
        if (!trimmed.regionMatches(true, 0, "SELECT", 0, 6)) {
            throw new IllegalArgumentException(name + " 必须以 SELECT 开头");
        }
        // 简单防多语句
        if (containsStatementSeparator(trimmed)) {
            throw new IllegalArgumentException(name + " 不允许包含多条语句 (分号 ;)");
        }
    }

    private static boolean containsStatementSeparator(String sql) {
        // 允许结尾的单个 ; , 不允许中间出现; 简单实现: 去掉末尾 ; 后再判断
        String s = sql.endsWith(";") ? sql.substring(0, sql.length() - 1) : sql;
        return s.contains(";");
    }

    private static int countQuestionMarks(String sql) {
        int n = 0;
        boolean inSingle = false, inDouble = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'' && !inDouble) inSingle = !inSingle;
            else if (c == '"' && !inSingle) inDouble = !inDouble;
            else if (c == '?' && !inSingle && !inDouble) n++;
        }
        return n;
    }

    private static String buildPlaceholders(int n) {
        StringBuilder sb = new StringBuilder(n * 2);
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(',');
            sb.append('?');
        }
        return sb.toString();
    }
}
