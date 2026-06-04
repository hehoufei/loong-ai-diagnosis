package cn.aimstek.loong.aidiag.storagetask;

import cn.aimstek.loong.aidiag.storagetask.dto.StorageTaskRecord;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 把每个完成的任务追加到 CSV 文件:
 *   ~/.loong-ai-diagnosis/storage-task-history.csv
 * 进程崩溃 / 重启都不会丢失.
 */
@Slf4j
public final class StorageHistoryWriter {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String CSV_HEADER =
            "writtenAt,seq,round,step,taskNo,taskType,startNode,endNode,"
            + "submittedAt,finishedAt,durationSec,state,dbTaskState,stuck,alarmCount,alarmMessages,remark,"
            + "cellCode,cellAisle,cellSide,cellDepth,cellRow,cellCol,cellLayer";

    /**
     * 库位编码尾段正则:
     *   ...-AL_L08-L-01-02-0003-01
     *           ↑   ↑   ↑   ↑   ↑   ↑
     *          aisle side depth row col layer
     */
    private static final java.util.regex.Pattern CODE_RE = java.util.regex.Pattern.compile(
            ".*-AL_L(\\d{1,3})-([LR])-(\\d{1,2})-(\\d{1,2})-(\\d{1,4})-(\\d{1,2})$");

    private StorageHistoryWriter() {}

    public static synchronized void append(File csvFile, StorageTaskRecord rec) {
        if (rec == null) return;
        try {
            File dir = csvFile.getParentFile();
            if (dir != null && !dir.exists()) //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();

            boolean exist = csvFile.exists() && csvFile.length() > 0;
            if (exist) {
                migrateHeaderIfNeeded(csvFile);
            }
            try (BufferedWriter w = new BufferedWriter(
                    new OutputStreamWriter(new FileOutputStream(csvFile, true), StandardCharsets.UTF_8))) {
                if (!exist) {
                    w.write('\ufeff');                  // BOM, Excel 直接打开中文不乱码
                    w.write(CSV_HEADER);
                    w.newLine();
                }
                w.write(toCsvLine(rec));
                w.newLine();
            }
        } catch (Exception e) {
            log.warn("写入任务历史 CSV 失败: {}", e.getMessage());
        }
    }

    public static String toCsvLine(StorageTaskRecord r) {
        long dur = parseDurationSec(r.getSubmittedAt(), r.getFinishedAt());
        // 优先用 endNode 作为"分析视角"的库位; N2N 任务 endNode 是入库口节点, 解析失败留空
        String[] parsed = parseCellCode(r.getEndNode());
        if (parsed[0].isEmpty()) {
            // endNode 不是库位 (出库 / 输送) 时, 退回 startNode (出库 startNode 是库位)
            parsed = parseCellCode(r.getStartNode());
        }
        return String.join(",",
                csv(LocalDateTime.now().format(TS_FMT)),
                String.valueOf(r.getSeq()),
                String.valueOf(r.getRound()),
                csv(r.getStep()),
                csv(r.getTaskNo()),
                csv(r.getTaskType()),
                csv(r.getStartNode()),
                csv(r.getEndNode()),
                csv(r.getSubmittedAt()),
                csv(r.getFinishedAt()),
                dur >= 0 ? String.valueOf(dur) : "",
                csv(r.getState()),
                csv(r.getDbTaskState()),
                String.valueOf(r.isStuck()),
                String.valueOf(r.getAlarmCount()),
                csv(joinAlarms(r.getAlarmMessages())),
                csv(r.getRemark()),
                csv(parsed[0]),         // cellCode (取自端点的库位编码)
                csv(parsed[1]),         // cellAisle
                csv(parsed[2]),         // cellSide
                csv(parsed[3]),         // cellDepth
                csv(parsed[4]),         // cellRow
                csv(parsed[5]),         // cellCol
                csv(parsed[6])          // cellLayer
        );
    }

    /**
     * 取一条任务记录用于"分析视角"的库位编码 (endNode 优先, 失败退回 startNode).
     * 供日报聚合等复用, 保证与 CSV 中 cellCode 列口径一致.
     */
    public static String cellCodeOf(StorageTaskRecord r) {
        if (r == null) return "";
        String[] parsed = parseCellCode(r.getEndNode());
        if (parsed[0].isEmpty()) {
            parsed = parseCellCode(r.getStartNode());
        }
        return parsed[0];
    }

    /**
     * 解析库位编码, 返回 [code, aisle, side, depth, row, col, layer], 失败全空字符串
     */
    private static String[] parseCellCode(String node) {
        String[] empty = new String[]{"", "", "", "", "", "", ""};
        if (node == null || node.isBlank()) return empty;
        java.util.regex.Matcher m = CODE_RE.matcher(node);
        if (!m.matches()) return empty;
        return new String[]{
                node,
                String.valueOf(Integer.parseInt(m.group(1))),     // aisle
                m.group(2),                                       // side L/R
                String.valueOf(Integer.parseInt(m.group(3))),     // depth
                String.valueOf(Integer.parseInt(m.group(4))),     // row
                String.valueOf(Integer.parseInt(m.group(5))),     // col
                String.valueOf(Integer.parseInt(m.group(6)))      // layer
        };
    }

    private static long parseDurationSec(String start, String end) {
        if (start == null || end == null) return -1;
        try {
            LocalDateTime s = LocalDateTime.parse(start, TS_FMT);
            LocalDateTime e = LocalDateTime.parse(end, TS_FMT);
            return java.time.Duration.between(s, e).getSeconds();
        } catch (Exception ex) {
            return -1;
        }
    }

    /** 多条报警明细拼成一段, 用 " | " 分隔 */
    private static String joinAlarms(java.util.List<String> msgs) {
        if (msgs == null || msgs.isEmpty()) return "";
        return String.join(" | ", msgs);
    }

    /** CSV 字段转义: 含逗号 / 引号 / 换行的用双引号包裹, 内部双引号转义为两个 */
    private static String csv(String v) {
        if (v == null) return "";
        boolean needsQuote = v.indexOf(',') >= 0 || v.indexOf('"') >= 0
                || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0;
        if (!needsQuote) return v;
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }

    /**
     * 检测旧 CSV 文件的 header 是否缺少新增列 (如 alarmCount/alarmMessages),
     * 如果缺少则在 header 的 stuck 后面插入缺失列, 并在所有数据行相应位置插入空值.
     * 只执行一次 (检测到 header 已包含 alarmCount 时直接返回).
     */
    public static void migrateIfNeeded(File csvFile) {
        migrateHeaderIfNeeded(csvFile);
    }

    private static void migrateHeaderIfNeeded(File csvFile) {
        try {
            java.util.List<String> lines = java.nio.file.Files.readAllLines(csvFile.toPath(), StandardCharsets.UTF_8);
            if (lines.isEmpty()) return;
            String header = lines.get(0);
            if (header.startsWith("\ufeff")) header = header.substring(1);
            if (header.contains("alarmCount")) return; // 已是新格式

            // 旧格式: ...stuck,remark,...  新格式: ...stuck,alarmCount,alarmMessages,remark,...
            // 找到 stuck 列的位置, 在其后插入 alarmCount,alarmMessages
            String[] cols = header.split(",", -1);
            int stuckIdx = -1;
            for (int i = 0; i < cols.length; i++) {
                if ("stuck".equalsIgnoreCase(cols[i].trim())) { stuckIdx = i; break; }
            }
            if (stuckIdx < 0) return; // 找不到 stuck 列, 无法安全迁移

            // 重建每一行: 在 stuckIdx 后面插入 2 个空列
            java.util.List<String> newLines = new java.util.ArrayList<>(lines.size());
            // header
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i <= stuckIdx; i++) {
                if (i > 0) sb.append(',');
                sb.append(cols[i]);
            }
            sb.append(",alarmCount,alarmMessages");
            for (int i = stuckIdx + 1; i < cols.length; i++) {
                sb.append(',').append(cols[i]);
            }
            // 保留 BOM
            String bom = lines.get(0).startsWith("\ufeff") ? "\ufeff" : "";
            newLines.add(bom + sb);

            // 数据行
            for (int lineIdx = 1; lineIdx < lines.size(); lineIdx++) {
                String line = lines.get(lineIdx);
                if (line.isBlank()) { newLines.add(line); continue; }
                // 简单按逗号分割 (旧格式 remark 不含逗号的情况下安全; 含引号字段需要更精细的解析)
                String[] fields = splitCsvLine(line);
                StringBuilder row = new StringBuilder();
                for (int i = 0; i <= Math.min(stuckIdx, fields.length - 1); i++) {
                    if (i > 0) row.append(',');
                    row.append(fields[i]);
                }
                // 补齐 stuck 之前缺失的字段
                for (int i = fields.length; i <= stuckIdx; i++) {
                    row.append(',');
                }
                row.append(",0,"); // alarmCount=0, alarmMessages=空
                for (int i = stuckIdx + 1; i < fields.length; i++) {
                    row.append(',').append(fields[i]);
                }
                newLines.add(row.toString());
            }
            java.nio.file.Files.write(csvFile.toPath(), newLines, StandardCharsets.UTF_8);
            log.info("CSV 格式迁移完成 (添加 alarmCount/alarmMessages 列): {}", csvFile.getName());
        } catch (Exception e) {
            log.warn("CSV 格式迁移失败 {}: {}", csvFile.getName(), e.getMessage());
        }
    }

    /** 支持引号的 CSV 行分割 */
    private static String[] splitCsvLine(String line) {
        java.util.List<String> fields = new java.util.ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuote && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    sb.append('"'); i++;
                } else {
                    inQuote = !inQuote;
                }
                sb.append(c);
            } else if (c == ',' && !inQuote) {
                fields.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        fields.add(sb.toString());
        return fields.toArray(new String[0]);
    }
}
