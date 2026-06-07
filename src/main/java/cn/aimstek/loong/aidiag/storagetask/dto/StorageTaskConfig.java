package cn.aimstek.loong.aidiag.storagetask.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 立库库位循环测试 全局配置
 * 持久化到 ~/.loong-ai-diagnosis/storage-task-config.json
 */
@Data
public class StorageTaskConfig {

    // ========== 数据库 ==========
    private String dbUrl = "jdbc:mysql://localhost:3306/loong-platform"
            + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false";
    private String dbUsername = "root";
    private String dbPassword = "root";

    // ========== ACS addTask ==========
    private String acsAddTaskUrl = System.getenv("ACS_ADD_TASK_URL") != null
            ? System.getenv("ACS_ADD_TASK_URL")
            : "http://localhost:8088/task/addTask";
    /** 查询任务状态的接口 URL (GET, 拼接 ?taskNo=xxx). 为空时自动从 acsAddTaskUrl 推导同域地址. */
    private String taskDetailUrl;

    /** 如果 taskDetailUrl 未配置, 从 acsAddTaskUrl 推导出同域的查询接口地址 */
    public String getTaskDetailUrl() {
        if (taskDetailUrl != null && !taskDetailUrl.isBlank()) {
            return taskDetailUrl;
        }
        // 从 acsAddTaskUrl (如 http://10.15.81.15:8088/task/addTask) 推导基地址
        String base = acsAddTaskUrl;
        if (base != null && base.contains("/task/addTask")) {
            base = base.substring(0, base.indexOf("/task/addTask"));
        } else if (base != null) {
            // 取到端口号为止
            int idx = base.indexOf("://");
            if (idx > 0) {
                int slashAfterHost = base.indexOf('/', idx + 3);
                if (slashAfterHost > 0) {
                    base = base.substring(0, slashAfterHost);
                }
            }
        }
        return (base == null ? "http://localhost:8088" : base)
                + "/api/admin/scheduler/task/detail/getTaskDetail";
    }
    private int httpTimeoutSeconds = 15;

    // ========== 库位编码规则 ==========
    private String storagePrefix = "SL_-WH_001-SA_HSMD_1-AL_L";
    /** 巷道列表, 默认只测 8 巷道 */
    private List<Integer> aisles = new ArrayList<>(List.of(8));
    private int totalLayers = 10;
    private int maxCol = 64;
    /** 全测的层(其他层做随机抽样) */
    private List<Integer> fullTestLayers = new ArrayList<>(List.of(1, 10));
    /** 非全测层抽样比例 */
    private double sampleRatio = 0.5;
    /** 随机种子, 保证可复现; null 则每次随机 */
    private Long randomSeed = 20260525L;

    /**
     * S 形条带宽度 (列数). 决定 generate() 出来的库位顺序.
     *   stripeWidth = maxCol -> 整个仓库一段 S, 列方向最少跨越
     *   stripeWidth = 4      -> 每 4 列一段 (默认)
     *   stripeWidth = 1      -> 每列一段, 层升降密集
     */
    private int stripeWidth = 4;

    // ========== 任务参数 ==========
    /** 入库口节点 (一轮起点 N2S 的 startNode + 输送终点 N2N 的 endNode) */
    private String inboundStartNode = "ND_11001";
    /** 出库口节点 (一轮 S2N 的 endNode + 输送起点 N2N 的 startNode) */
    private String outboundEndNode = "ND_11002";
    private String taskSource = "WMS";
    private String taskBizType = "默认";
    /** 容器 (固定) */
    private String containerCode = "C_1224";
    /** 货物 (固定) */
    private String goodsCode = "GS_1223";

    /**
     * 是否在每轮出库后追加一个 N2N 输送任务 (ND_11002 -> ND_11001).
     * 输送任务携带 SHAPE_DETECTOR 形状检测功能, 容器高度 30cm.
     */
    private boolean enableConveyorStep = true;

    // ========== 循环规则 ==========
    /** 一轮内移库次数 */
    private int shuffleTimesPerRound = 10;

    /** 所有库位访问完一遍后自动停止 (true=停, false=继续无限循环). 默认 true. */
    private boolean stopWhenAllVisited = true;

    /**
     * 移库目标选取策略.
     *   SEQUENTIAL: 顺序滚动 (取 validCodes[(cursor+1) % n], 路径最短, 覆盖最快)
     *   LOCAL:      局部随机 (在 cursor ± moveLocalWindow 内随机抽)
     *   RANDOM:     完全随机 (在整个 validCodes 内随机, 三轴乱跳)
     */
    private String moveStrategy = "SEQUENTIAL";

    /** LOCAL 模式下随机窗口大小 (前后各 N 个候选) */
    private int moveLocalWindow = 10;

    /**
     * 是否避免同列同侧的移库 (默认 true).
     * 同列同侧 = 同巷道, 同层, 同列, 同侧 (左/右), 比如 row1 左远 -> row2 左近.
     * 双深位货架物理上需要先取出近位才能取远位, 测试场景一般不希望出现.
     */
    private boolean avoidSameColSameSide = true;

    // ========== 轮询 ==========
    private int pollIntervalSeconds = 3;
    /** 单任务轮询超时 (秒). 超时不会中止, 仅在 UI 标记为"卡住", 等待手动处理 */
    private long taskStuckThresholdSeconds = 30L * 60L;

    // ========== 堆垛机报警监控 ==========
    /**
     * 是否在任务执行期间轮询堆垛机设备缓存, 统计报警次数.
     * 与任务状态轮询同频 (pollIntervalSeconds).
     */
    private boolean enableCraneAlarmMonitor = true;

    /**
     * 设备缓存接口基地址 (不含路径).
     * 完整 URL = deviceCacheBaseUrl + /iot/deviceCache/client/get/{deviceCode}
     * 示例: http://10.15.81.15:8088
     */
    private String deviceCacheBaseUrl = "http://10.15.81.15:8088";

    /**
     * 巷道号 -> 堆垛机设备编码 的格式化模板, %d 处填巷道号.
     * 默认 DV_NO.A040%02d: 1 巷道 -> DV_NO.A04001 ... 8 巷道 -> DV_NO.A04008.
     */
    private String craneDeviceCodePattern = "DV_NO.A040%02d";

    /** 任务号前缀, 实际任务号 = prefix + yyyyMMddHHmmssSSS_序号 */
    private String taskNoPrefix = "ZDYNDTASK_";

    // ========== 自定义 SQL ==========
    /**
     * 校验库位是否存在的 SQL.
     * 必须以 SELECT 开头, 必须包含占位符 {CODES} (运行时替换为 ?,?,?...)
     * 第一列必须是库位编码列, 用于回填到结果集.
     *
     * 默认查询 map_storage_location 表 (真实库位表, 数十万行).
     * 如果你的 schema 不同, 可在 UI 上修改这条 SQL.
     */
    private String validateLocationSql =
            "SELECT storage_location_code FROM map_storage_location "
                    + "WHERE storage_location_code IN ({CODES}) "
                    + "AND activate = 'ON' AND delete_flag = 0";

    /**
     * 查询任务状态的 SQL.
     * 必须以 SELECT 开头, 必须含 1 个 ? 占位符 (绑定 task_no)
     * 第一列必须是 task_state.
     */
    private String queryTaskStateSql =
            "SELECT task_state FROM sc_task WHERE task_no = ? "
                    + "ORDER BY create_time DESC LIMIT 1";
}
