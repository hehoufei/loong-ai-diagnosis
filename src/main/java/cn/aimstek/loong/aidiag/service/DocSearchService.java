package cn.aimstek.loong.aidiag.service;

import cn.aimstek.loong.aidiag.dto.DiagnoseResponse;
import cn.aimstek.loong.aidiag.dto.RelevantDoc;
import cn.aimstek.loong.aidiag.dto.RootCauseItem;
import cn.aimstek.loong.aidiag.dto.TaskDetail;
import cn.aimstek.loong.aidiag.rule.RuleProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class DocSearchService {

    @Autowired(required = false)
    private VectorStore vectorStore;

    @Autowired(required = false)
    private RuleProperties ruleProperties;

    @Autowired(required = false)
    private HybridSearchService hybridSearchService;

    /**
     * 搜索相关文档（混合检索：向量语义 + 关键词过滤）
     * 原方法：DiagnosisStreamService.searchRelevantDocs()
     * 逻辑完全保持一致
     */
    public List<String> searchRelevantDocs(String query, List<String> keywords) {
        return searchRelevantDocs(query, keywords, null);
    }

    /**
     * 搜索相关文档，支持按 doc_category 过滤
     * @param category 文档分类，为 null 或空则不过滤
     */
    public List<String> searchRelevantDocs(String query, List<String> keywords, String category) {
        try {
            if (query == null || query.isBlank()) {
                return Collections.emptyList();
            }

            int topK = (ruleProperties != null && ruleProperties.getDocSearch() != null)
                ? ruleProperties.getDocSearch().getTopK() : 3;
            boolean hybridEnabled = (ruleProperties != null && ruleProperties.getDocSearch() != null)
                && ruleProperties.getDocSearch().isHybridEnabled();
            double threshold = (ruleProperties != null && ruleProperties.getDocSearch() != null)
                ? ruleProperties.getDocSearch().getSimilarityThreshold() : 0.6;

            List<Document> results;

            // 优先使用混合检索
            if (hybridEnabled && hybridSearchService != null) {
                results = hybridSearchService.hybridSearch(query, topK);
            } else if (vectorStore != null) {
                // 降级为纯向量检索 + 关键词过滤（原有逻辑）
                results = vectorStore.similaritySearch(
                    SearchRequest.builder()
                        .query(query)
                        .topK(topK)
                        .similarityThreshold(threshold)
                        .build()
                );

                if (results == null || results.isEmpty()) {
                    return Collections.emptyList();
                }

                // 纯向量检索模式下保留关键词过滤
                if (keywords != null && !keywords.isEmpty()) {
                    List<Document> filtered = results.stream()
                        .filter(doc -> {
                            String text = doc.getText().toLowerCase();
                            return keywords.stream().anyMatch(kw -> kw != null && text.contains(kw.toLowerCase()));
                        })
                        .collect(Collectors.toList());
                    if (!filtered.isEmpty()) {
                        results = filtered;
                    }
                }
            } else {
                return Collections.emptyList();
            }

            if (results == null || results.isEmpty()) {
                return Collections.emptyList();
            }

            // 按 doc_category 过滤
            if (category != null && !category.isBlank()) {
                results = results.stream()
                        .filter(doc -> {
                            if (doc.getMetadata() == null) return false;
                            Object cat = doc.getMetadata().get("doc_category");
                            return cat != null && category.equalsIgnoreCase(cat.toString());
                        })
                        .collect(Collectors.toList());
                if (results.isEmpty()) {
                    return Collections.emptyList();
                }
            }

            List<String> docContents = new ArrayList<>();
            for (Document doc : results) {
                String text = doc.getText();
                if (text != null && !text.isBlank()) {
                    // 截取摘要，避免过长
                    String content = text.length() > 500 ? text.substring(0, 500) + "..." : text;
                    String source = doc.getMetadata() != null
                        ? String.valueOf(doc.getMetadata().getOrDefault("source", ""))
                        : "";
                    if (!source.isEmpty()) {
                        content = "[" + source + "] " + content;
                    }
                    docContents.add(content);
                }
            }
            return docContents;
        } catch (Exception e) {
            log.warn("文档检索异常，不影响规则分析: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 从任务数据构建文档检索查询字符串
     * 原方法：DiagnosisStreamService.buildDocQuery()
     * 逻辑完全保持一致
     */
    public String buildDocQuery(TaskDetail detail, List<String> logs) {
        StringBuilder query = new StringBuilder();

        // 优先用 errorMessage
        if (detail.getErrorMessage() != null && !detail.getErrorMessage().isBlank()) {
            query.append(detail.getErrorMessage()).append(" ");
        }

        // 其次从日志中提取错误关键词
        if (logs != null) {
            for (String logLine : logs) {
                if (logLine != null) {
                    String lower = logLine.toLowerCase();
                    if (lower.contains("error") || lower.contains("exception") || lower.contains("异常") || lower.contains("失败")) {
                        // 截取关键部分
                        String snippet = logLine.length() > 100 ? logLine.substring(0, 100) : logLine;
                        query.append(snippet).append(" ");
                        if (query.length() > 300) break; // 限制查询长度
                    }
                }
            }
        }

        // 再次用设备类型 + 状态描述
        if (query.length() == 0) {
            Set<String> deviceTypes = new HashSet<>();
            for (TaskDetail.TaskItemDetail item : detail.getTaskItems()) {
                if (item.getDeviceType() != null) deviceTypes.add(item.getDeviceType());
            }
            if (!deviceTypes.isEmpty()) {
                query.append(String.join(" ", deviceTypes)).append(" ");
            }
            String state = detail.getHandleState() != null ? detail.getHandleState() : detail.getTaskState();
            if (state != null) {
                query.append("任务状态 ").append(state);
            }
        }

        return query.toString().trim();
    }

    /**
     * 为诊断结果追加文档检索信息
     * 原方法：DiagnosisStreamService.enrichWithDocSearch()
     * 逻辑完全保持一致
     */
    public void enrichWithDocSearch(DiagnoseResponse resp, TaskDetail detail, List<String> logs) {
        try {
            String query = buildDocQuery(detail, logs);
            if (query.isBlank()) return;

            // 提取关键词
            List<String> keywords = new ArrayList<>();
            if (detail.getErrorMessage() != null && !detail.getErrorMessage().isBlank()) {
                keywords.add(detail.getErrorMessage().length() > 20
                    ? detail.getErrorMessage().substring(0, 20) : detail.getErrorMessage());
            }
            for (TaskDetail.TaskItemDetail item : detail.getTaskItems()) {
                if (item.getDeviceType() != null) keywords.add(item.getDeviceType());
            }

            List<String> docs = searchRelevantDocs(query, keywords);
            if (docs.isEmpty()) return;

            // 将文档作为结构化的 RelevantDoc 列表设置到响应中
            List<RelevantDoc> relevantDocList = new ArrayList<>();
            for (String doc : docs) {
                String source = "";
                String content = doc;
                if (doc.startsWith("[") && doc.contains("] ")) {
                    int endBracket = doc.indexOf("] ");
                    source = doc.substring(1, endBracket);
                    content = doc.substring(endBracket + 2);
                }
                relevantDocList.add(new RelevantDoc(source, content));
            }
            resp.setRelevantDocs(relevantDocList);
        } catch (Exception e) {
            log.warn("文档检索增强失败，不影响诊断结果: {}", e.getMessage());
        }
    }

    /**
     * 预检索文档（新增方法，用于 DiagnosisContext 构建阶段）
     * 在规则匹配前预先检索相关文档
     */
    public List<String> preSearchDocs(TaskDetail detail, List<String> logs) {
        try {
            String query = buildDocQuery(detail, logs);
            if (query == null || query.isBlank()) {
                return List.of();
            }

            // 提取关键词
            List<String> keywords = new ArrayList<>();
            if (detail.getErrorMessage() != null && !detail.getErrorMessage().isBlank()) {
                keywords.add(detail.getErrorMessage().length() > 20
                    ? detail.getErrorMessage().substring(0, 20)
                    : detail.getErrorMessage());
            }
            if (detail.getTaskItems() != null) {
                for (TaskDetail.TaskItemDetail item : detail.getTaskItems()) {
                    if (item.getDeviceType() != null) {
                        keywords.add(item.getDeviceType());
                    }
                }
            }

            return searchRelevantDocs(query, keywords);
        } catch (Exception e) {
            log.warn("预检索文档异常: {}", e.getMessage(), e);
            return List.of();
        }
    }
}
