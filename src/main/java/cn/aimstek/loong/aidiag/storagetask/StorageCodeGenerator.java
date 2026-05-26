package cn.aimstek.loong.aidiag.storagetask;

import cn.aimstek.loong.aidiag.storagetask.dto.StorageTaskConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 立库库位编码生成 (蛇形 / boustrophedon 顺序)
 *
 * 编码格式:
 *   {prefix}{aisle:02d}-{side}-{depth:02d}-{row:02d}-{col:04d}-{layer:02d}
 *
 * row 与 侧/深度 的对应关系 (按现场 DB 真实数据):
 *   row 1 -> L 侧 远伸 (depth=02)
 *   row 2 -> L 侧 近伸 (depth=01)
 *   row 3 -> R 侧 近伸 (depth=01)
 *   row 4 -> R 侧 远伸 (depth=02)
 *
 * 排序策略 (使相邻库位物理距离最近, 减少 AGV/堆垛机回头):
 *   外层  aisle
 *   中层  layer  (1..N, 跨层交替方向, 蛇形)
 *   内层  col    (1..maxCol 或反向, 蛇形)
 *   每列内顺序:  row 1 -> 2 -> 3 -> 4  (左远 -> 左近 -> 右近 -> 右远, 每步都相邻)
 *
 * 抽样:
 *   - fullTestLayers 中的层 (默认 1, 10): 全部 64 列
 *   - 其他层: 等距取列, step = round(1 / sampleRatio)
 *             ratio=0.5 -> step=2 -> 取 1,3,5,...,63 共 32 列
 */
public final class StorageCodeGenerator {

    /** row -> side, 用于编码生成. */
    private static final Map<Integer, String> ROW_SIDE_MAP = Map.of(
            1, "L",
            2, "L",
            3, "R",
            4, "R"
    );

    /** row -> depth, 用于编码生成. */
    private static final Map<Integer, Integer> ROW_DEPTH_MAP = Map.of(
            1, 2,
            2, 1,
            3, 1,
            4, 2
    );

    /**
     * 同一列内 4 排的访问顺序 (跨侧交替):
     *   row1(L远) -> row3(R近) -> row2(L近) -> row4(R远)
     * 保证相邻两个库位始终不同侧, 避免同列同侧移库.
     */
    private static final int[] ROW_VISIT_ORDER = {1, 3, 2, 4};

    private StorageCodeGenerator() {
    }

    public static String buildCode(StorageTaskConfig cfg, int aisle, int row, int col, int layer) {
        String side = ROW_SIDE_MAP.get(row);
        int depth = ROW_DEPTH_MAP.get(row);
        return String.format("%s%02d-%s-%02d-%02d-%04d-%02d",
                cfg.getStoragePrefix(), aisle, side, depth, row, col, layer);
    }

    /**
     * 生成 S 形条带遍历:
     *   - 把列分成若干 stripeWidth 宽的"条带"
     *   - 每个条带内部, layer 1->N 或 N->1 升降; 层间列方向蛇形交替
     *   - 条带间, 层方向交替 (升一段, 降一段)
     *
     *   stripeWidth=maxCol -> 整个仓库一个 S, 列方向少跨越, 层方向频繁
     *   stripeWidth=1      -> 每列一个 S, 层升降密集
     *   推荐 4~16 之间, 兼顾测试覆盖与堆垛机效率
     */
    public static List<String> generate(StorageTaskConfig cfg) {
        List<String> codes = new ArrayList<>();
        List<Integer> aisles = (cfg.getAisles() != null) ? cfg.getAisles() : List.of();
        List<Integer> fullLayers = (cfg.getFullTestLayers() != null) ? cfg.getFullTestLayers() : List.of();
        int maxCol = cfg.getMaxCol();
        int stripeWidth = Math.max(1, cfg.getStripeWidth() > 0 ? cfg.getStripeWidth() : maxCol);

        for (Integer aisle : aisles) {
            int stripeIdx = 0;
            for (int colStart = 1; colStart <= maxCol; colStart += stripeWidth) {
                int colEnd = Math.min(colStart + stripeWidth - 1, maxCol);
                boolean stripeReverse = (stripeIdx % 2 == 1);

                int layerFrom = stripeReverse ? cfg.getTotalLayers() : 1;
                int layerTo   = stripeReverse ? 1 : cfg.getTotalLayers();
                int layerStep = stripeReverse ? -1 : 1;

                // 这一段内, 列方向从段首到段末蛇形
                // 第一行从 colStart 走到 colEnd (顺向), 第二行反向, 依此交替
                // 但 stripeReverse 段需要让"段首库位"接得住上一段段末: 上一段段末是 (layer=N, col=段末A); 现在段首是 (layer=N, col=段末A+1) 顺向继续, 所以 stripeReverse 段首行也是顺向
                boolean colReverseInStripe = false;
                int layer = layerFrom;
                while (true) {
                    boolean fullTest = fullLayers.contains(layer);
                    List<Integer> cols = pickColumnsInRange(
                            colStart, colEnd, maxCol, fullTest, cfg.getSampleRatio());
                    if (colReverseInStripe) Collections.reverse(cols);

                    for (int col : cols) {
                        for (int row : ROW_VISIT_ORDER) {
                            codes.add(buildCode(cfg, aisle, row, col, layer));
                        }
                    }
                    colReverseInStripe = !colReverseInStripe;

                    if (layer == layerTo) break;
                    layer += layerStep;
                }
                stripeIdx++;
            }
        }
        return codes;
    }

    /**
     * 从 [colStart, colEnd] 取列, 抽样规则与全局保持一致.
     * 全测层 / ratio>=1 -> 全部
     * 否则 step = round(1/ratio), 从全局 1, 1+step, ... 中保留落入区间内的
     */
    private static List<Integer> pickColumnsInRange(int colStart, int colEnd, int maxCol,
                                                     boolean fullTest, double sampleRatio) {
        if (fullTest || sampleRatio >= 1.0) {
            List<Integer> all = new ArrayList<>();
            for (int c = colStart; c <= colEnd; c++) all.add(c);
            return all;
        }
        if (sampleRatio <= 0) return new ArrayList<>();
        int step = Math.max(1, (int) Math.round(1.0 / sampleRatio));
        List<Integer> chosen = new ArrayList<>();
        for (int c = 1; c <= maxCol; c += step) {
            if (c >= colStart && c <= colEnd) chosen.add(c);
        }
        return chosen;
    }
}
