package cn.aimstek.loong.aidiag.tool;

import cn.aimstek.loong.aidiag.config.AgentProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Agent 工具：从运维文档和设备手册中检索相关知识片段，辅助诊断分析。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocSearchTool {

    private final VectorStore vectorStore;
    private final AgentProperties agentProperties;

    @Tool(description = "从运维文档和设备手册中检索与查询相关的知识片段，用于辅助诊断分析")
    public String searchDocs(@ToolParam(description = "检索查询语句") String query) {
        long start = System.currentTimeMillis();
        try {
            int topK = agentProperties.getRagTopK();
            log.info("DocSearchTool 开始检索, query={}, topK={}", shorten(query), topK);
            List<Document> results = vectorStore.similaritySearch(
                    SearchRequest.builder().query(query).topK(topK).build()
            );

            if (results == null || results.isEmpty()) {
                log.info("DocSearchTool 检索完成, 耗时={}ms, 无结果", System.currentTimeMillis() - start);
                return "未找到相关文档。知识库可能为空或没有匹配的内容，请基于内置知识继续诊断。";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("检索到 ").append(results.size()).append(" 条相关文档片段：\n\n");
            for (int i = 0; i < results.size(); i++) {
                Document doc = results.get(i);
                String source = doc.getMetadata() != null
                        ? String.valueOf(doc.getMetadata().getOrDefault("source", "未知来源"))
                        : "未知来源";
                sb.append("【").append(i + 1).append("】来源: ").append(source).append("\n");
                sb.append(doc.getText()).append("\n\n");
            }
            log.info("DocSearchTool 检索完成, 耗时={}ms, 命中={} 条", System.currentTimeMillis() - start, results.size());
            return sb.toString();
        } catch (Exception e) {
            log.warn("文档检索失败, 耗时={}ms, error={}", System.currentTimeMillis() - start, e.getMessage(), e);
            return "文档检索失败: " + e.getMessage() + "。请基于内置知识继续诊断。";
        }
    }

    private String shorten(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 80 ? text.substring(0, 80) + "..." : text;
    }
}
