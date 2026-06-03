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
}
