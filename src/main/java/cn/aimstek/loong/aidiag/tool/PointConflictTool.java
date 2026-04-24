package cn.aimstek.loong.aidiag.tool;

import cn.aimstek.loong.aidiag.dto.PointConflict;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.*;

/**
 * Agent 工具：查询指定点位列表的锁定状态，排除当前任务自身的锁
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PointConflictTool {

    private static final long SLOW_QUERY_WARN_MS = 1000L;
    private static final long VERY_SLOW_QUERY_WARN_MS = 3000L;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @PostConstruct
    public void warmUp() {
        long start = System.currentTimeMillis();
        try {
            Integer count = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            log.info("PointConflictTool 数据源预热完成, result={}, cost={}ms", count, System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.warn("PointConflictTool 数据源预热失败, cost={}ms, error={}", System.currentTimeMillis() - start, e.getMessage(), e);
        }
    }

    @Tool(description = "查询指定点位列表的锁定状态，返回被其他任务占用的点位信息，用于判断是否存在路径冲突。需排除当前任务自身的锁")
    public String checkPointConflicts(
            @ToolParam(description = "当前任务ID，用于排除自身的锁") String taskId,
            @ToolParam(description = "需要检查的点位编码列表，逗号分隔，如: ND_20013,ND_20014") String points) {
        long start = System.currentTimeMillis();
        try {
            log.info("PointConflictTool 开始查询, taskId={}, points={}", taskId, points);
            String[] pointArray = points.split(",");
            Set<String> matchValues = new LinkedHashSet<>();
            for (String p : pointArray) {
                String trimmed = p.trim();
                if (!trimmed.isEmpty()) {
                    matchValues.add(trimmed);
                    matchValues.add("ND_" + trimmed);
                }
            }
            if (matchValues.isEmpty()) {
                log.info("PointConflictTool 查询完成, 耗时={}ms, 无有效点位", System.currentTimeMillis() - start);
                return "[]";
            }

            String placeholders = String.join(",", Collections.nCopies(matchValues.size(), "?"));
            String sql = "SELECT lock_value, lock_type, parent_lock_key, expiration_time " +
                         "FROM loong_lock WHERE lock_value IN (" + placeholders + ") " +
                         "AND expiration_time > NOW()";

            List<PointConflict> allLocks = jdbcTemplate.query(sql, (rs, rowNum) -> {
                PointConflict c = new PointConflict();
                c.setLockValue(rs.getString("lock_value"));
                c.setLockType(rs.getString("lock_type"));
                String parentKey = rs.getString("parent_lock_key");
                String occupiedBy = parentKey;
                if (parentKey != null && parentKey.startsWith("JOB_")) {
                    occupiedBy = parentKey.substring(4);
                }
                c.setOccupiedBy(occupiedBy);
                Timestamp ts = rs.getTimestamp("expiration_time");
                c.setExpirationTime(ts != null ? ts.toLocalDateTime() : null);
                return c;
            }, matchValues.toArray());

            List<PointConflict> conflicts = new ArrayList<>();
            for (PointConflict c : allLocks) {
                String occ = c.getOccupiedBy();
                if (occ != null && !occ.equals(taskId)) {
                    conflicts.add(c);
                }
            }

            long cost = System.currentTimeMillis() - start;
            if (cost >= VERY_SLOW_QUERY_WARN_MS) {
                log.warn("PointConflictTool 查询非常慢, cost={}ms, taskId={}, points={}, 冲突数={}", cost, taskId, points, conflicts.size());
            } else if (cost >= SLOW_QUERY_WARN_MS) {
                log.warn("PointConflictTool 查询偏慢, cost={}ms, taskId={}, points={}, 冲突数={}", cost, taskId, points, conflicts.size());
            } else {
                log.info("PointConflictTool 查询完成, 耗时={}ms, 冲突数={}", cost, conflicts.size());
            }
            return objectMapper.writeValueAsString(conflicts);
        } catch (Exception e) {
            log.warn("查询点位锁冲突失败, 耗时={}ms, error={}", System.currentTimeMillis() - start, e.getMessage(), e);
            return "查询点位锁冲突失败: " + e.getMessage() + "。请尝试其他诊断方式。";
        }
    }
}
