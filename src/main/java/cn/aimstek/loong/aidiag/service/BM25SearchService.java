package cn.aimstek.loong.aidiag.service;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class BM25SearchService {

    // BM25 参数
    private static final double K1 = 1.2;
    private static final double B = 0.75;

    // 中文+英文分词正则：按空格、标点、中文字符边界分割
     private static final Pattern TOKENIZE_PATTERN = Pattern.compile("[\\s\\p{Punct}\uFF0C\u3002\uFF01\uFF1F\u3001\uFF1B\uFF1A\u201C\u201D\u2018\u2019\uFF08\uFF09\u3010\u3011\u300A\u300B\u3000]+");

    // 倒排索引：term -> {docId -> termFrequency}
    private final Map<String, Map<String, Integer>> invertedIndex = new ConcurrentHashMap<>();
    // 文档长度
    private final Map<String, Integer> docLengths = new ConcurrentHashMap<>();
    // 文档内容缓存
    private final Map<String, Document> docStore = new ConcurrentHashMap<>();
    // 文档分类映射：docId -> category
    private final Map<String, String> docCategoryMap = new ConcurrentHashMap<>();
    // 平均文档长度
    private volatile double avgDocLength = 0;
    // 索引是否已构建
    private volatile boolean indexed = false;

    /**
     * 对查询执行 BM25 检索
     */
    public List<ScoredDocument> search(String query, int topK) {
        return search(query, topK, null);
    }

    /**
     * 对查询执行 BM25 检索，支持按 category 过滤
     */
    public List<ScoredDocument> search(String query, int topK, String category) {
        if (!indexed || query == null || query.isBlank()) {
            return Collections.emptyList();
        }

        List<String> queryTerms = tokenize(query);
        if (queryTerms.isEmpty()) return Collections.emptyList();

        // 如果指定了 category，确定需要评分的文档集合
        Set<String> candidateDocIds = null;
        if (category != null && !category.isBlank()) {
            candidateDocIds = docCategoryMap.entrySet().stream()
                    .filter(e -> category.equalsIgnoreCase(e.getValue()))
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toSet());
            if (candidateDocIds.isEmpty()) {
                return Collections.emptyList();
            }
        }

        int totalDocs = docStore.size();
        Map<String, Double> scores = new HashMap<>();

        for (String term : queryTerms) {
            Map<String, Integer> postings = invertedIndex.get(term.toLowerCase());
            if (postings == null) continue;

            int df = postings.size(); // 包含该词的文档数
            double idf = Math.log((totalDocs - df + 0.5) / (df + 0.5) + 1.0);

            for (Map.Entry<String, Integer> entry : postings.entrySet()) {
                String docId = entry.getKey();
                // category 过滤
                if (candidateDocIds != null && !candidateDocIds.contains(docId)) {
                    continue;
                }
                int tf = entry.getValue();
                int docLen = docLengths.getOrDefault(docId, 1);

                double tfNorm = (tf * (K1 + 1)) / (tf + K1 * (1 - B + B * docLen / avgDocLength));
                double score = idf * tfNorm;

                scores.merge(docId, score, Double::sum);
            }
        }

        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> new ScoredDocument(docStore.get(e.getKey()), e.getValue()))
                .filter(sd -> sd.getDocument() != null)
                .collect(Collectors.toList());
    }

    /**
     * 构建/重建索引
     * 在文档导入后调用
     */
    public void buildIndex(List<Document> documents) {
        invertedIndex.clear();
        docLengths.clear();
        docStore.clear();
        docCategoryMap.clear();

        if (documents == null || documents.isEmpty()) {
            indexed = false;
            log.info("BM25索引为空，无文档可索引");
            return;
        }

        long totalLength = 0;

        for (Document doc : documents) {
            String docId = doc.getId();
            String text = doc.getText();
            if (docId == null || text == null || text.isBlank()) continue;

            docStore.put(docId, doc);

            // 记录文档分类
            Map<String, Object> meta = doc.getMetadata();
            if (meta != null) {
                Object cat = meta.get("doc_category");
                if (cat != null) {
                    docCategoryMap.put(docId, cat.toString());
                }
            }

            // 将元数据关键字段追加到分词内容中
            StringBuilder textBuilder = new StringBuilder(text);
            if (meta != null) {
                Object title = meta.get("doc_title");
                if (title != null) textBuilder.append(" ").append(title);
                Object category = meta.get("doc_category");
                if (category != null) textBuilder.append(" ").append(category);
                Object keywords = meta.get("keywords");
                if (keywords != null) textBuilder.append(" ").append(keywords);
            }

            List<String> tokens = tokenize(textBuilder.toString());
            docLengths.put(docId, tokens.size());
            totalLength += tokens.size();

            // 统计词频
            Map<String, Integer> termFreq = new HashMap<>();
            for (String token : tokens) {
                String lower = token.toLowerCase();
                if (lower.length() >= 1) { // 保留单字符（中文单字有意义）
                    termFreq.merge(lower, 1, Integer::sum);
                }
            }

            // 更新倒排索引
            for (Map.Entry<String, Integer> entry : termFreq.entrySet()) {
                invertedIndex.computeIfAbsent(entry.getKey(), k -> new ConcurrentHashMap<>())
                        .put(docId, entry.getValue());
            }
        }

        avgDocLength = docStore.isEmpty() ? 0 : (double) totalLength / docStore.size();
        indexed = true;
        log.info("BM25索引构建完成: {} 篇文档, {} 个词条, 平均文档长度 {}",
                docStore.size(), invertedIndex.size(), String.format("%.1f", avgDocLength));
    }

    /**
     * 检查索引是否可用
     */
    public boolean isIndexed() {
        return indexed;
    }

    /**
     * 中文+英文分词
     * 策略：按标点/空格分割，中文按单字和双字组合（unigram + bigram）
     */
    private List<String> tokenize(String text) {
        if (text == null || text.isBlank()) return Collections.emptyList();

        List<String> tokens = new ArrayList<>();
        String[] segments = TOKENIZE_PATTERN.split(text);

        for (String segment : segments) {
            if (segment.isEmpty()) continue;

            // 判断是否包含中文
            if (containsChinese(segment)) {
                // 中文：unigram + bigram
                for (int i = 0; i < segment.length(); i++) {
                    char c = segment.charAt(i);
                    if (c >= '\u4e00' && c <= '\u9fff') {
                        tokens.add(String.valueOf(c)); // unigram
                        if (i + 1 < segment.length()) {
                            char next = segment.charAt(i + 1);
                            if (next >= '\u4e00' && next <= '\u9fff') {
                                tokens.add("" + c + next); // bigram
                            }
                        }
                    }
                }
            } else {
                // 英文/数字：整个作为一个token
                if (segment.length() >= 2) {
                    tokens.add(segment.toLowerCase());
                }
            }
        }
        return tokens;
    }

    private boolean containsChinese(String str) {
        for (char c : str.toCharArray()) {
            if (c >= '\u4e00' && c <= '\u9fff') return true;
        }
        return false;
    }

    @Getter
    public static class ScoredDocument {
        private final Document document;
        private final double score;

        public ScoredDocument(Document document, double score) {
            this.document = document;
            this.score = score;
        }
    }
}
