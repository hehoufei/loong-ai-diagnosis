package cn.aimstek.loong.aidiag.storagetask;

import cn.aimstek.loong.aidiag.storagetask.dto.DailyReport;
import cn.aimstek.loong.aidiag.storagetask.dto.StorageTaskRunnerState;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 每日跑库报告服务.
 * 从 CSV 历史文件中按日期聚合生成报告, 结果持久化到 JSON 文件.
 */
@Slf4j
@Service
public class DailyReportService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final StorageTaskRunnerManager manager;
    private final ObjectMapper mapper;

    public DailyReportService(StorageTaskRunnerManager manager, ObjectMapper objectMapper) {
        this.manager = manager;
        ObjectMapper m = objectMapper.copy();
        m.enable(SerializationFeature.INDENT_OUTPUT);
        this.mapper = m;
    }

    /**
     * 获取某天全部巷道的每日报告.
     */
    public List<DailyReport> getReports(String date) {
        List<Integer> aisles = manager.getLoadedAisles();
        List<DailyReport> reports = new ArrayList<>();
        for (int aisle : aisles) {
            DailyReport r = getOrGenerate(date, aisle);
            if (r != null) reports.add(r);
        }
        return reports;
    }

    /**
     * 获取某天某巷道的报告.
     */
    public DailyReport getReport(String date, int aisle) {
        return getOrGenerate(date, aisle);
    }

    /**
     * 获取日期范围内的报告 (趋势).
     */
    public List<DailyReport> getReportRange(String from, String to) {
        LocalDate start = LocalDate.parse(from, DATE_FMT);
        LocalDate end = LocalDate.parse(to, DATE_FMT);
        List<Integer> aisles = manager.getLoadedAisles();

        List<DailyReport> all = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            String dateStr = d.format(DATE_FMT);
            // 生成每天的汇总 (所有巷道合计)
            DailyReport summary = new DailyReport();
            summary.setDate(dateStr);
            summary.setAisle(0); // 0 表示全巷道汇总

            boolean hasData = false;
            for (int aisle : aisles) {
                DailyReport r = getOrGenerate(dateStr, aisle);
                if (r != null && r.getTotalIssued() > 0) {
                    hasData = true;
                    mergeInto(summary, r);
                }
            }
            if (hasData) {
                // 计算汇总的成功率和平均
                int total = summary.getTotalIssued();
                if (total > 0) {
                    summary.setSuccessRate(Math.round((summary.getTotalSuccess() + summary.getTotalManualSuccess()) * 1000.0 / total) / 10.0);
                }
                if (summary.getTotalDuration() != null && summary.getTotalDuration() > 0) {
                    int cnt = summary.getTotalSuccess() + summary.getTotalManualSuccess() + summary.getTotalCancel();
                    if (cnt > 0) summary.setAvgDuration(Math.round(summary.getTotalDuration() * 10.0 / cnt) / 10.0);
                }
                all.add(summary);
            }
        }
        return all;
    }

    /**
     * 手动触发生成/刷新指定日期的报告.
     */
    public List<DailyReport> generate(String date) {
        List<Integer> aisles = manager.getLoadedAisles();
        List<DailyReport> reports = new ArrayList<>();
        for (int aisle : aisles) {
            DailyReport r = generateFromCsv(date, aisle);
            if (r != null) {
                saveReport(date, aisle, r);
                reports.add(r);
            }
        }
        return reports;
    }

    // ====================================================================

    private DailyReport getOrGenerate(String date, int aisle) {
        // 先看缓存文件
        DailyReport cached = loadReport(date, aisle);
        // 如果是今天的数据, 总是重新生成 (实时性)
        String today = LocalDate.now().format(DATE_FMT);
        if (cached != null && !date.equals(today)) {
            return cached;
        }
        // 生成
        DailyReport r = generateFromCsv(date, aisle);
        if (r != null) {
            saveReport(date, aisle, r);
        }
        return r;
    }

    /**
     * 从 CSV 历史文件中提取指定日期的记录并汇总.
     */
    private DailyReport generateFromCsv(String date, int aisle) {
        StorageTaskRunner runner = manager.getRunner(aisle);
        if (runner == null) return null;

        File csvFile = runner.historyFile();
        if (!csvFile.exists()) return null;

        List<CsvRow> rows = parseCsv(csvFile, date);
        if (rows.isEmpty()) {
            // 即便没有 CSV 记录, 也返回一个空报告 (带覆盖度信息)
            DailyReport r = new DailyReport();
            r.setDate(date);
            r.setAisle(aisle);
            StorageTaskRunnerState state = runner.getState();
            r.setTotalCodes(state.getValidCodes() != null ? state.getValidCodes().size() : 0);
            r.setVisitedCodes(state.getVisitedCodes() != null ? state.getVisitedCodes().size() : 0);
            if (r.getTotalCodes() > 0) {
                r.setCoveragePct(Math.round(r.getVisitedCodes() * 1000.0 / r.getTotalCodes()) / 10.0);
            }
            return r;
        }

        DailyReport r = new DailyReport();
        r.setDate(date);
        r.setAisle(aisle);

        // 覆盖度信息
        StorageTaskRunnerState state = runner.getState();
        r.setTotalCodes(state.getValidCodes() != null ? state.getValidCodes().size() : 0);
        r.setVisitedCodes(state.getVisitedCodes() != null ? state.getVisitedCodes().size() : 0);
        if (r.getTotalCodes() > 0) {
            r.setCoveragePct(Math.round(r.getVisitedCodes() * 1000.0 / r.getTotalCodes()) / 10.0);
        }

        // 遍历当天记录
        int totalIssued = 0;
        int success = 0, manual = 0, cancel = 0, fail = 0, skipped = 0, stuck = 0;
        int inbound = 0, shuffle = 0, outbound = 0, conveyor = 0;
        long totalDur = 0;
        int durCount = 0;
        int maxDur = Integer.MIN_VALUE, minDur = Integer.MAX_VALUE;
        long inDur = 0, inCnt = 0, shDur = 0, shCnt = 0, outDur = 0, outCnt = 0;
        int alarms = 0;
        Integer startRound = null, endRound = null;
        Set<Integer> completedRounds = new HashSet<>();
        String earliest = null, latest = null;
        Set<String> todayVisited = new HashSet<>();
        List<DailyReport.AbnormalTask> abnormals = new ArrayList<>();

        for (CsvRow row : rows) {
            totalIssued++;

            // 状态统计
            String st = row.state != null ? row.state.toUpperCase() : "";
            switch (st) {
                case "SUCCESS" -> success++;
                case "MANUAL_SUCCESS" -> manual++;
                case "CANCEL", "CANCELED", "CANCELLED" -> cancel++;
                case "FAIL", "FAILED" -> fail++;
                case "SKIPPED" -> skipped++;
            }
            if (row.stuck) stuck++;

            // 类型统计
            String type = row.taskType != null ? row.taskType.toUpperCase() : "";
            switch (type) {
                case "N2S" -> inbound++;
                case "S2S" -> shuffle++;
                case "S2N" -> outbound++;
                case "N2N" -> conveyor++;
            }

            // 耗时
            if (row.durationSec != null && row.durationSec >= 0) {
                totalDur += row.durationSec;
                durCount++;
                maxDur = Math.max(maxDur, row.durationSec);
                minDur = Math.min(minDur, row.durationSec);
                switch (type) {
                    case "N2S" -> { inDur += row.durationSec; inCnt++; }
                    case "S2S" -> { shDur += row.durationSec; shCnt++; }
                    case "S2N" -> { outDur += row.durationSec; outCnt++; }
                }
            }

            // 报警
            alarms += row.alarmCount;

            // 轮次
            if (row.round > 0) {
                if (startRound == null || row.round < startRound) startRound = row.round;
                if (endRound == null || row.round > endRound) endRound = row.round;
                // 出库完成 = 一轮结束
                if ("S2N".equalsIgnoreCase(row.taskType) && ("SUCCESS".equalsIgnoreCase(st) || "MANUAL_SUCCESS".equalsIgnoreCase(st))) {
                    completedRounds.add(row.round);
                }
            }

            // 时间范围
            if (row.submittedAt != null && !row.submittedAt.isBlank()) {
                if (earliest == null || row.submittedAt.compareTo(earliest) < 0) earliest = row.submittedAt;
            }
            if (row.finishedAt != null && !row.finishedAt.isBlank()) {
                if (latest == null || row.finishedAt.compareTo(latest) > 0) latest = row.finishedAt;
            }

            // 今日新访问库位
            if (row.cellCode != null && !row.cellCode.isBlank()) {
                todayVisited.add(row.cellCode);
            }

            // 异常清单
            if ("FAIL".equalsIgnoreCase(st) || "FAILED".equalsIgnoreCase(st)
                    || row.stuck || row.alarmCount > 0) {
                DailyReport.AbnormalTask at = new DailyReport.AbnormalTask();
                at.setTime(row.submittedAt);
                at.setAisle(aisle);
                at.setTaskNo(row.taskNo);
                at.setTaskType(row.taskType);
                at.setState(row.state);
                String issue = "";
                if ("FAIL".equalsIgnoreCase(st) || "FAILED".equalsIgnoreCase(st)) issue = "任务失败";
                else if (row.stuck) issue = "超时卡住";
                if (row.alarmCount > 0) issue += (issue.isEmpty() ? "" : "+") + "报警" + row.alarmCount + "次";
                at.setIssue(issue);
                abnormals.add(at);
            }
        }

        r.setTotalIssued(totalIssued);
        r.setTotalSuccess(success);
        r.setTotalManualSuccess(manual);
        r.setTotalCancel(cancel);
        r.setTotalFail(fail);
        r.setTotalSkipped(skipped);
        r.setTotalStuck(stuck);
        r.setInboundCount(inbound);
        r.setShuffleCount(shuffle);
        r.setOutboundCount(outbound);
        r.setConveyorCount(conveyor);
        r.setTotalAlarms(alarms);
        r.setStartRound(startRound);
        r.setEndRound(endRound);
        r.setCompletedRounds(completedRounds.size());
        r.setRunStartTime(earliest);
        r.setRunEndTime(latest);
        r.setNewVisitedToday(todayVisited.size());
        r.setAbnormalTasks(abnormals.size() > 50 ? abnormals.subList(0, 50) : abnormals);

        if (durCount > 0) {
            r.setAvgDuration(Math.round(totalDur * 10.0 / durCount) / 10.0);
            r.setMaxDuration(maxDur == Integer.MIN_VALUE ? null : maxDur);
            r.setMinDuration(minDur == Integer.MAX_VALUE ? null : minDur);
            r.setTotalDuration(totalDur);
        }
        if (inCnt > 0) r.setAvgInboundDuration(Math.round(inDur * 10.0 / inCnt) / 10.0);
        if (shCnt > 0) r.setAvgShuffleDuration(Math.round(shDur * 10.0 / shCnt) / 10.0);
        if (outCnt > 0) r.setAvgOutboundDuration(Math.round(outDur * 10.0 / outCnt) / 10.0);

        // 成功率
        if (totalIssued > 0) {
            r.setSuccessRate(Math.round((success + manual) * 1000.0 / totalIssued) / 10.0);
        }

        // 有效运行分钟数
        if (earliest != null && latest != null) {
            try {
                LocalDateTime s = LocalDateTime.parse(earliest, TS_FMT);
                LocalDateTime e = LocalDateTime.parse(latest, TS_FMT);
                r.setEffectiveMinutes((int) ChronoUnit.MINUTES.between(s, e));
            } catch (Exception ignored) {}
        }

        // 补充内存中未归档任务的报警 (currentTask + recentTasks 中今天的、CSV 未统计到的)
        supplementAlarmsFromMemory(r, runner, date, rows);

        return r;
    }

    /**
     * 从内存 state 中补充报警数据.
     * CSV 只包含已归档的任务, 正在运行的 currentTask 和刚归档但还在 recentTasks 里的任务
     * 可能有报警没被 CSV 统计到 (因为 CSV 写入有延迟, 或任务还没结束).
     */
    private void supplementAlarmsFromMemory(DailyReport r, StorageTaskRunner runner, String date, List<CsvRow> csvRows) {
        StorageTaskRunnerState state = runner.getState();
        if (state == null) return;

        // 收集 CSV 中已统计的 taskNo, 避免重复计数
        Set<String> csvTaskNos = new HashSet<>();
        for (CsvRow row : csvRows) {
            if (row.taskNo != null) csvTaskNos.add(row.taskNo);
        }

        int extraAlarms = 0;

        // 检查 currentTask (正在执行, 尚未归档)
        var current = state.getCurrentTask();
        if (current != null && current.getAlarmCount() > 0) {
            String submittedDate = current.getSubmittedAt() != null && current.getSubmittedAt().length() >= 10
                    ? current.getSubmittedAt().substring(0, 10) : null;
            if (date.equals(submittedDate) && !csvTaskNos.contains(current.getTaskNo())) {
                extraAlarms += current.getAlarmCount();
                // 加入异常清单
                DailyReport.AbnormalTask at = new DailyReport.AbnormalTask();
                at.setTime(current.getSubmittedAt());
                at.setAisle(r.getAisle());
                at.setTaskNo(current.getTaskNo());
                at.setTaskType(current.getTaskType());
                at.setState(current.getState());
                at.setIssue("报警" + current.getAlarmCount() + "次 (执行中)");
                r.getAbnormalTasks().add(at);
            }
        }

        // 检查 recentTasks (已归档在内存, 但可能还没被写到 CSV 或刚写入还没被本次读取覆盖)
        var recent = state.getRecentTasks();
        if (recent != null) {
            for (var rec : recent) {
                if (rec.getAlarmCount() <= 0) continue;
                if (csvTaskNos.contains(rec.getTaskNo())) continue; // CSV 已统计
                String submittedDate = rec.getSubmittedAt() != null && rec.getSubmittedAt().length() >= 10
                        ? rec.getSubmittedAt().substring(0, 10) : null;
                if (!date.equals(submittedDate)) continue;
                extraAlarms += rec.getAlarmCount();
                // 加入异常清单
                DailyReport.AbnormalTask at = new DailyReport.AbnormalTask();
                at.setTime(rec.getSubmittedAt());
                at.setAisle(r.getAisle());
                at.setTaskNo(rec.getTaskNo());
                at.setTaskType(rec.getTaskType());
                at.setState(rec.getState());
                at.setIssue("报警" + rec.getAlarmCount() + "次");
                r.getAbnormalTasks().add(at);
            }
        }

        if (extraAlarms > 0) {
            r.setTotalAlarms(r.getTotalAlarms() + extraAlarms);
        }
    }

    /**
     * 合并单个巷道报告到汇总报告.
     */
    private void mergeInto(DailyReport summary, DailyReport r) {
        summary.setTotalIssued(summary.getTotalIssued() + r.getTotalIssued());
        summary.setTotalSuccess(summary.getTotalSuccess() + r.getTotalSuccess());
        summary.setTotalManualSuccess(summary.getTotalManualSuccess() + r.getTotalManualSuccess());
        summary.setTotalCancel(summary.getTotalCancel() + r.getTotalCancel());
        summary.setTotalFail(summary.getTotalFail() + r.getTotalFail());
        summary.setTotalSkipped(summary.getTotalSkipped() + r.getTotalSkipped());
        summary.setTotalStuck(summary.getTotalStuck() + r.getTotalStuck());
        summary.setInboundCount(summary.getInboundCount() + r.getInboundCount());
        summary.setShuffleCount(summary.getShuffleCount() + r.getShuffleCount());
        summary.setOutboundCount(summary.getOutboundCount() + r.getOutboundCount());
        summary.setConveyorCount(summary.getConveyorCount() + r.getConveyorCount());
        summary.setTotalAlarms(summary.getTotalAlarms() + r.getTotalAlarms());
        summary.setCompletedRounds(summary.getCompletedRounds() + r.getCompletedRounds());
        summary.setNewVisitedToday(summary.getNewVisitedToday() + r.getNewVisitedToday());
        summary.setTotalCodes(summary.getTotalCodes() + r.getTotalCodes());
        summary.setVisitedCodes(summary.getVisitedCodes() + r.getVisitedCodes());

        if (r.getTotalDuration() != null) {
            long prev = summary.getTotalDuration() != null ? summary.getTotalDuration() : 0;
            summary.setTotalDuration(prev + r.getTotalDuration());
        }
        if (r.getMaxDuration() != null) {
            if (summary.getMaxDuration() == null || r.getMaxDuration() > summary.getMaxDuration())
                summary.setMaxDuration(r.getMaxDuration());
        }
        if (r.getMinDuration() != null) {
            if (summary.getMinDuration() == null || r.getMinDuration() < summary.getMinDuration())
                summary.setMinDuration(r.getMinDuration());
        }

        // 合并异常列表
        summary.getAbnormalTasks().addAll(r.getAbnormalTasks());
    }

    // ============ CSV 解析 ============

    private List<CsvRow> parseCsv(File csvFile, String targetDate) {
        List<CsvRow> result = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(csvFile), StandardCharsets.UTF_8))) {
            String header = br.readLine();
            if (header == null) return result;
            // 去 BOM
            if (header.startsWith("\ufeff")) header = header.substring(1);
            String[] cols = parseCsvLine(header);
            Map<String, Integer> colIdx = new HashMap<>();
            for (int i = 0; i < cols.length; i++) colIdx.put(cols[i].trim(), i);

            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] fields = parseCsvLine(line);

                // 用 submittedAt 或 writtenAt 判断日期
                String submittedAt = getField(fields, colIdx, "submittedAt");
                String writtenAt = getField(fields, colIdx, "writtenAt");
                String dateRef = submittedAt != null && submittedAt.length() >= 10 ? submittedAt : writtenAt;
                if (dateRef == null || dateRef.length() < 10) continue;
                String rowDate = dateRef.substring(0, 10);
                if (!rowDate.equals(targetDate)) continue;

                CsvRow row = new CsvRow();
                row.submittedAt = submittedAt;
                row.finishedAt = getField(fields, colIdx, "finishedAt");
                row.taskNo = getField(fields, colIdx, "taskNo");
                row.taskType = getField(fields, colIdx, "taskType");
                row.state = getField(fields, colIdx, "state");
                row.cellCode = getField(fields, colIdx, "cellCode");
                row.stuck = "true".equalsIgnoreCase(getField(fields, colIdx, "stuck"));

                String durStr = getField(fields, colIdx, "durationSec");
                if (durStr != null && !durStr.isBlank()) {
                    try { row.durationSec = Integer.parseInt(durStr.trim()); }
                    catch (NumberFormatException ignored) {}
                }
                String alarmStr = getField(fields, colIdx, "alarmCount");
                if (alarmStr != null && !alarmStr.isBlank()) {
                    try { row.alarmCount = Integer.parseInt(alarmStr.trim()); }
                    catch (NumberFormatException ignored) {}
                }
                String roundStr = getField(fields, colIdx, "round");
                if (roundStr != null && !roundStr.isBlank()) {
                    try { row.round = Integer.parseInt(roundStr.trim()); }
                    catch (NumberFormatException ignored) {}
                }

                result.add(row);
            }
        } catch (IOException e) {
            log.warn("解析 CSV 失败 {}: {}", csvFile.getName(), e.getMessage());
        }
        return result;
    }

    private String getField(String[] fields, Map<String, Integer> colIdx, String colName) {
        Integer idx = colIdx.get(colName);
        if (idx == null || idx >= fields.length) return null;
        String v = fields[idx];
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    /**
     * 简单 CSV 行解析 (支持双引号包裹内含逗号).
     */
    private String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuote) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        sb.append('"');
                        i++;
                    } else {
                        inQuote = false;
                    }
                } else {
                    sb.append(c);
                }
            } else {
                if (c == '"') {
                    inQuote = true;
                } else if (c == ',') {
                    fields.add(sb.toString());
                    sb.setLength(0);
                } else {
                    sb.append(c);
                }
            }
        }
        fields.add(sb.toString());
        return fields.toArray(new String[0]);
    }

    // ============ 持久化 ============

    private File reportDir() {
        File dir = new File(System.getProperty("user.home"), ".loong-ai-diagnosis/daily-reports");
        if (!dir.exists()) //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        return dir;
    }

    private File reportFile(String date, int aisle) {
        return new File(reportDir(), "report-" + date + "-aisle" + aisle + ".json");
    }

    private DailyReport loadReport(String date, int aisle) {
        File f = reportFile(date, aisle);
        if (!f.exists()) return null;
        try {
            return mapper.readValue(f, DailyReport.class);
        } catch (Exception e) {
            log.debug("加载报告文件失败: {}", f.getName());
            return null;
        }
    }

    private void saveReport(String date, int aisle, DailyReport report) {
        try {
            mapper.writeValue(reportFile(date, aisle), report);
        } catch (IOException e) {
            log.warn("保存报告文件失败: {}", e.getMessage());
        }
    }

    private static class CsvRow {
        String submittedAt;
        String finishedAt;
        String taskNo;
        String taskType;
        String state;
        String cellCode;
        boolean stuck;
        Integer durationSec;
        int alarmCount;
        int round;
    }
}
