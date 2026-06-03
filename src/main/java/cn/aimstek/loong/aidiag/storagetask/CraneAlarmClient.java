package cn.aimstek.loong.aidiag.storagetask;

import cn.aimstek.loong.aidiag.storagetask.dto.StorageTaskConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 堆垛机设备缓存查询客户端.
 *
 * 接口: GET {deviceCacheBaseUrl}/iot/deviceCache/client/get/{deviceCode}
 * 取 data.stateInfo.alarmInfoList 中的报警, 关注 alarmMessage 字段.
 *
 * 不引入新依赖, 使用 HttpURLConnection + Jackson, 与 StorageAcsClient 风格一致.
 */
@Slf4j
public class CraneAlarmClient {

    private final StorageTaskConfig cfg;
    private final ObjectMapper mapper;

    public CraneAlarmClient(StorageTaskConfig cfg, ObjectMapper mapper) {
        this.cfg = cfg;
        this.mapper = mapper;
    }

    /**
     * 根据巷道号拼出堆垛机设备编码, 例如巷道 8 -> DV_NO.A04008.
     */
    public String deviceCodeOfAisle(int aisle) {
        String pattern = cfg.getCraneDeviceCodePattern();
        if (pattern == null || pattern.isBlank()) {
            pattern = "DV_NO.A040%02d";
        }
        return String.format(pattern, aisle);
    }

    /**
     * 查询指定堆垛机当前的报警快照. 失败返回 null (调用方据此跳过本次累计).
     */
    public Snapshot fetch(String deviceCode) {
        if (deviceCode == null || deviceCode.isBlank()) {
            return null;
        }
        String base = cfg.getDeviceCacheBaseUrl();
        if (base == null || base.isBlank()) {
            return null;
        }
        String url = base.replaceAll("/+$", "")
                + "/iot/deviceCache/client/get/"
                + URLEncoder.encode(deviceCode, StandardCharsets.UTF_8);

        HttpURLConnection conn = null;
        try {
            URL u = URI.create(url).toURL();
            conn = (HttpURLConnection) u.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            int timeoutMs = Math.max(1, cfg.getHttpTimeoutSeconds()) * 1000;
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);

            int code = conn.getResponseCode();
            String body = readBody(conn);
            if (code >= 300) {
                log.debug("查询堆垛机报警 HTTP {} ({}): {}", code, deviceCode, body);
                return null;
            }
            return parse(body);
        } catch (Exception e) {
            log.debug("查询堆垛机报警失败 deviceCode={}: {}", deviceCode, e.getMessage());
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private Snapshot parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode alarmNode = root.path("data").path("stateInfo").path("alarmInfoList");
            Snapshot snap = new Snapshot();
            if (alarmNode.isArray()) {
                for (JsonNode n : alarmNode) {
                    Alarm a = new Alarm();
                    a.setAlarmType(n.path("alarmType").asText(null));
                    a.setAlarmCode(n.path("alarmCode").asText(null));
                    a.setAlarmMessage(n.path("alarmMessage").asText(null));
                    a.setFirstAlarmTime(n.path("firstAlarmTime").asText(null));
                    snap.getAlarms().add(a);
                }
            }
            return snap;
        } catch (Exception e) {
            log.debug("解析堆垛机报警 JSON 失败: {}", e.getMessage());
            return null;
        }
    }

    private String readBody(HttpURLConnection conn) {
        try {
            InputStream is = (conn.getResponseCode() >= 400) ? conn.getErrorStream() : conn.getInputStream();
            if (is == null) {
                return "";
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /** 一次查询得到的报警快照 */
    @Data
    public static class Snapshot {
        private List<Alarm> alarms = new ArrayList<>();
    }

    /** 单条报警 */
    @Data
    public static class Alarm {
        private String alarmType;
        private String alarmCode;
        private String alarmMessage;
        private String firstAlarmTime;

        /** 去重键: 同一条报警(类型+编码+首次报警时间)在持续期间只计一次 */
        public String dedupKey() {
            return (alarmType == null ? "" : alarmType) + "|"
                    + (alarmCode == null ? "" : alarmCode) + "|"
                    + (firstAlarmTime == null ? "" : firstAlarmTime);
        }
    }
}
