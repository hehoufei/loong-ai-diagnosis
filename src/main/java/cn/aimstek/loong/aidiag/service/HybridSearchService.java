package cn.aimstek.loong.aidiag.service;

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
public class HybridSearchService {

    private static final int RRF_K = 60; // RRF 常数

    @Autowired(required = false)
    private VectorStore vectorStore;

    @Autowired
    private BM25SearchService bm25SearchService;

    @Autowired
    private RuleProperties ruleProperties;

    /**
     * 混合检索：向量语义 + BM25 关键词，RRF 融合
     */
    public List<Document> hybridSearch(String query, int topK) {
        if (query == null || query.isBlank()) return Collections.emptyList();

        try {
            RuleProperties.DocSearchConfig config = ruleProperties.getDocSearch();

            // 1. 向量语义检索
            List<Document> vectorResults = Collections.emptyList();
            if (vectorStore != null) {
                try {
                    vectorResults = vectorStore.similaritySearch(
                        SearchRequest.builder()
                            .query(query)
                            .topK(topK * 2) // 检索更多候选，用于融合
                            .similarityThreshold(config.getSimilarityThreshold())
                            .build()
                    );
                } catch (Exception e) {
                    log.warn("向量检索异常: {}", e.getMessage());
                }
            }

            // 2. BM25 关键词检索
            List<BM25SearchService.ScoredDocument> bm25Results = Collections.emptyList();
            if (bm25SearchService.isIndexed()) {
                try {
                    bm25Results = bm25SearchService.search(query, topK * 2);
                } catch (Exception e) {
                    log.warn("BM25检索异常: {}", e.getMessage());
                }
            }

            // 3. 如果只有一路有结果，直接返回
            if (vectorResults.isEmpty() && bm25Results.isEmpty()) {
                return Collections.emptyList();
            }
            if (bm25Results.isEmpty()) {
                return vectorResults.stream().limit(topK).collect(Collectors.toList());
            }
            if (vectorResults.isEmpty()) {
                return bm25Results.stream()
                    .map(BM25SearchService.ScoredDocument::getDocument)
                    .limit(topK)
                    .collect(Collectors.toList());
            }

            // 4. RRF 融合
            Map<String, Double> fusedScores = new HashMap<>();
            Map<String, Document> docMap = new HashMap<>();

            // 向量检索结果排名
            for (int i = 0; i < vectorResults.size(); i++) {
                Document doc = vectorResults.get(i);
                String docId = doc.getId();
                if (docId == null) docId = "vec_" + i;
                double rrfScore = 1.0 / (RRF_K + i + 1);
                fusedScores.merge(docId, rrfScore, Double::sum);
                docMap.putIfAbsent(docId, doc);
            }

            // BM25 检索结果排名
            for (int i = 0; i < bm25Results.size(); i++) {
                Document doc = bm25Results.get(i).getDocument();
                String docId = doc.getId();
                if (docId == null) docId = "bm25_" + i;
                double rrfScore = 1.0 / (RRF_K + i + 1);
                fusedScores.merge(docId, rrfScore, Double::sum);
                docMap.putIfAbsent(docId, doc);
            }

            // 5. 按融合分数排序，返回 topK
            return fusedScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> docMap.get(e.getKey()))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        } catch (Exception e) {
            log.warn("混合检索异常: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }
}
