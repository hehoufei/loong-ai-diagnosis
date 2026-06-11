package cn.aimstek.loong.aidiag.controller;

import cn.aimstek.loong.aidiag.common.BaseResponse;
import cn.aimstek.loong.aidiag.common.Response;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

/**
 * 自定义任务模板管理 API
 * 持久化到 ~/.loong-ai-diagnosis/custom-templates.json
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/custom-templates")
public class CustomTemplateController {

    private final ObjectMapper mapper;
    private List<CustomTemplate> templates = new ArrayList<>();

    public CustomTemplateController(ObjectMapper mapper) {
        ObjectMapper m = mapper.copy();
        m.enable(SerializationFeature.INDENT_OUTPUT);
        this.mapper = m;
    }

    @PostConstruct
    public void init() {
        File dir = dataDir();
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        try {
            templates = loadTemplates();
        } catch (Exception e) {
            log.error("加载自定义模板失败", e);
            templates = new ArrayList<>();
        }
    }

    // ============== 获取所有模板 ==============

    @GetMapping
    public Response<List<CustomTemplate>> list() {
        return BaseResponse.success(templates);
    }

    // ============== 创建模板 ==============

    @PostMapping
    public Response<CustomTemplate> create(@RequestBody CustomTemplate tpl) {
        if (tpl.getId() == null || tpl.getId().isBlank()) {
            tpl.setId("tpl_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 4));
        }
        if (tpl.getCreatedAt() == null || tpl.getCreatedAt().isBlank()) {
            tpl.setCreatedAt(java.time.LocalDateTime.now().toString());
        }
        templates.add(tpl);
        save();
        return BaseResponse.success(tpl);
    }

    // ============== 更新模板 ==============

    @PutMapping("/{id}")
    public Response<CustomTemplate> update(@PathVariable String id, @RequestBody CustomTemplate tpl) {
        int idx = findIndex(id);
        if (idx < 0) {
            return BaseResponse.failure("NOT_FOUND", "模板不存在: " + id);
        }
        tpl.setId(id);
        tpl.setUpdatedAt(java.time.LocalDateTime.now().toString());
        if (tpl.getCreatedAt() == null || tpl.getCreatedAt().isBlank()) {
            tpl.setCreatedAt(templates.get(idx).getCreatedAt());
        }
        templates.set(idx, tpl);
        save();
        return BaseResponse.success(tpl);
    }

    // ============== 删除模板 ==============

    @DeleteMapping("/{id}")
    public Response<Void> delete(@PathVariable String id) {
        int idx = findIndex(id);
        if (idx < 0) {
            return BaseResponse.failure("NOT_FOUND", "模板不存在: " + id);
        }
        templates.remove(idx);
        save();
        return BaseResponse.success(null);
    }

    // ============== 调整排序 ==============

    @PostMapping("/reorder")
    public Response<Void> reorder(@RequestBody List<String> ids) {
        List<CustomTemplate> reordered = new ArrayList<>();
        for (String id : ids) {
            templates.stream().filter(t -> t.getId().equals(id)).findFirst().ifPresent(reordered::add);
        }
        // 保留未在列表中的
        for (CustomTemplate t : templates) {
            if (!ids.contains(t.getId())) reordered.add(t);
        }
        templates = reordered;
        save();
        return BaseResponse.success(null);
    }

    // ============== 导出/导入 ==============

    @GetMapping("/export")
    public Response<List<CustomTemplate>> export() {
        return BaseResponse.success(templates);
    }

    @PostMapping("/import")
    public Response<Integer> importTemplates(@RequestBody List<CustomTemplate> imported) {
        int count = 0;
        for (CustomTemplate tpl : imported) {
            if (tpl.getId() == null || tpl.getId().isBlank()) {
                tpl.setId("tpl_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 4));
            }
            // 如果已存在同 id，覆盖
            int idx = findIndex(tpl.getId());
            if (idx >= 0) {
                templates.set(idx, tpl);
            } else {
                templates.add(tpl);
            }
            count++;
        }
        save();
        return BaseResponse.success(count);
    }

    // ============== 内部方法 ==============

    private int findIndex(String id) {
        for (int i = 0; i < templates.size(); i++) {
            if (templates.get(i).getId().equals(id)) return i;
        }
        return -1;
    }

    private void save() {
        try {
            mapper.writeValue(dataFile(), templates);
        } catch (IOException e) {
            log.error("保存自定义模板失败", e);
        }
    }

    private List<CustomTemplate> loadTemplates() throws IOException {
        File file = dataFile();
        if (!file.exists()) return new ArrayList<>();
        byte[] bytes = Files.readAllBytes(file.toPath());
        if (bytes.length == 0) return new ArrayList<>();
        return mapper.readValue(bytes, new TypeReference<List<CustomTemplate>>() {});
    }

    private File dataDir() {
        return new File(System.getProperty("user.home"),
                ".loong-ai-diagnosis");
    }

    private File dataFile() {
        return new File(dataDir(), "custom-templates.json");
    }

    // ============== DTO ==============

    @Data
    public static class CustomTemplate {
        /** 唯一 ID */
        private String id;
        /** 模板名称 */
        private String name;
        /** 图标 emoji */
        private String icon;
        /** 分组名 */
        private String group;
        /** 类型: single / group */
        private String type;

        // ====== 单任务字段 ======
        /** 任务类型: N2N / N2S / S2N / S2S */
        private String taskType;
        /** 默认起点 */
        private String defaultStartNode;
        /** 默认终点 */
        private String defaultEndNode;
        /** 任务来源 */
        private String taskSource;
        /** 业务类型 */
        private String bizType;
        /** 优先级 */
        private Integer bizPriority;
        /** 独立运行 */
        private String independentRun;
        /** 备注 */
        private String remark;
        /** 前置开始任务号 */
        private String preStartTaskNo;
        /** 前置结束任务号 */
        private String preEndTaskNo;
        /** 期望开始时间 */
        private String expectedStartTime;
        /** 期望完成时间 */
        private String expectedFinishTime;
        /** 功能列表，每项包含 functionType 和可选 extData */
        private List<FunctionItem> functions;
        /** 容器信息 */
        private Map<String, Object> containerInfo;
        /** 货物列表 */
        private List<Map<String, Object>> goodsInfoList;
        /** 额外字段定义（用于 UI 展示可编辑的额外参数） */
        private List<ExtraField> extraFields;

        // ====== 任务组字段 ======
        /** 组类型 */
        private String groupType;
        /** 主任务配置 */
        private TaskConfig mainTask;
        /** 子任务配置 */
        private TaskConfig subTask;

        /** 创建时间 */
        private String createdAt;
        /** 更新时间 */
        private String updatedAt;
    }

    @Data
    public static class FunctionItem {
        private String functionType;
        private Map<String, Object> extData;
    }

    @Data
    public static class ExtraField {
        /** 字段 key（对应 payload 中的路径） */
        private String key;
        /** 显示名称 */
        private String label;
        /** 默认值 */
        private String defaultValue;
        /** 字段类型: text / select / number */
        private String fieldType;
        /** 下拉选项（fieldType=select 时使用） */
        private List<String> options;
        /** 放入 extData 的哪个 functionType 下（可选） */
        private String targetFunction;
    }

    @Data
    public static class TaskConfig {
        private String taskType;
        private String defaultStartNode;
        private String defaultEndNode;
        private String independentRun;
        private String taskSource;
        private String bizType;
        private Integer bizPriority;
        private String remark;
        private String preStartTaskNo;
        private String preEndTaskNo;
        private String expectedStartTime;
        private String expectedFinishTime;
        /** containerList 或 containerInfo */
        private String containerFieldName;
        private List<FunctionItem> functions;
        private Map<String, Object> containerInfo;
        private List<Map<String, Object>> goodsInfoList;
        private List<ExtraField> extraFields;
    }
}
