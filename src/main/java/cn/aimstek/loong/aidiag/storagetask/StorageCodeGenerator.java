package cn.aimstek.loong.aidiag.storagetask;

import cn.aimstek.loong.aidiag.storagetask.dto.StorageTaskConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 立库库位编码生成
 *
 * 规则:
 *   {prefix}{aisle:02d}-{side}-{depth:02d}-{row:02d}-{col:04d}-{layer:02d}
 *   L 侧: row=01,03    R 侧: row=02,04
 *   row 01/02 -> depth=01 (近伸); row 03/04 -> depth=02 (远伸)
 *   层 1 / 最高层 全测; 其他层随机抽样 sampleRatio
 */
public final class StorageCodeGenerator {

    private static final Map<String, int[]> SIDE_ROW_MAP = Map.of(
            "L", new int[]{1, 3},
            "R", new int[]{2, 4}
    );

    private static final Map<Integer, Integer> ROW_DEPTH_MAP = Map.of(
            1, 1,
            2, 1,
            3, 2,
            4, 2
    );

    private StorageCodeGenerator() {
    }

    public static String buildCode(StorageTaskConfig cfg, int aisle, String side, int row, int col, int layer) {
        int depth = ROW_DEPTH_MAP.getOrDefault(row, 1);
        return String.format("%s%02d-%s-%02d-%02d-%04d-%02d",
                cfg.getStoragePrefix(), aisle, side, depth, row, col, layer);
    }

    public static List<String> generate(StorageTaskConfig cfg) {
        Random random = (cfg.getRandomSeed() != null) ? new Random(cfg.getRandomSeed()) : new Random();
        List<String> codes = new ArrayList<>();
        List<Integer> aisles = (cfg.getAisles() != null) ? cfg.getAisles() : List.of();
        List<Integer> fullLayers = (cfg.getFullTestLayers() != null) ? cfg.getFullTestLayers() : List.of();

        for (Integer aisle : aisles) {
            for (int layer = 1; layer <= cfg.getTotalLayers(); layer++) {
                boolean fullTest = fullLayers.contains(layer);

                for (Map.Entry<String, int[]> entry : SIDE_ROW_MAP.entrySet()) {
                    String side = entry.getKey();
                    for (int row : entry.getValue()) {
                        List<Integer> chosen = pickColumns(random, cfg.getMaxCol(),
                                fullTest, cfg.getSampleRatio());
                        for (int col : chosen) {
                            codes.add(buildCode(cfg, aisle, side, row, col, layer));
                        }
                    }
                }
            }
        }
        return codes;
    }

    private static List<Integer> pickColumns(Random random, int maxCol, boolean fullTest, double sampleRatio) {
        List<Integer> all = new ArrayList<>(maxCol);
        for (int c = 1; c <= maxCol; c++) {
            all.add(c);
        }
        if (fullTest) {
            return all;
        }
        int sampleN = Math.max(1, (int) Math.round(maxCol * sampleRatio));
        Collections.shuffle(all, random);
        List<Integer> chosen = new ArrayList<>(all.subList(0, sampleN));
        Collections.sort(chosen);
        return chosen;
    }
}
