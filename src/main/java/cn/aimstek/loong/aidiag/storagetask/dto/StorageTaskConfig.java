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
    private String acsAddTaskUrl = "http://10.15.58.249:8088/task/addTask";
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

    // ========== 任务参数 ==========
    /** 入库口 / 出库口 节点 */
    private String inboundStartNode = "ND_11025";
    private String taskSource = "WMS";
    private String taskBizType = "默认";
    /** 容器 (固定) */
    private String containerCode = "C_1224";
    /** 货物 (固定) */
    private String goodsCode = "GS_1223";

    // ========== 循环规则 ==========
    /** 一轮内移库次数 */
    private int shuffleTimesPerRound = 10;

    // ========== 轮询 ==========
    private int pollIntervalSeconds = 3;
    /** 单任务轮询超时 (秒). 超时不会中止, 仅在 UI 标记为"卡住", 等待手动处理 */
    private long taskStuckThresholdSeconds = 30L * 60L;

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
