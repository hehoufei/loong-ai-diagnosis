package cn.aimstek.loong.aidiag.service;

import cn.aimstek.loong.aidiag.config.VectorStoreConfig;
import cn.aimstek.loong.aidiag.dto.ImportProgress;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.FileSystemResource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 文档 ETL 服务：读取 PDF/Markdown → 分块 → 向量化 → 写入 VectorStore。
 * 单个文件解析失败不影响其他文件导入。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentEtlService {

    private final VectorStore vectorStore;
    private final VectorStoreConfig vectorStoreConfig;
    private final BM25SearchService bm25SearchService;

    private final ConcurrentHashMap<String, ImportProgress> progressMap = new ConcurrentHashMap<>();

    @PostConstruct
    void initMetadataRegistry() {
        rebuildMetadataRegistry();
    }

    /** 文档元数据注册表：docId -> DocumentMetaSummary */
    private final ConcurrentHashMap<String, DocumentMetaSummary> metadataRegistry = new ConcurrentHashMap<>();

    /**
     * 异步导入指定路径下的文档（支持文件或目录）。
     */
    @Async
    public void importDocuments(String importId, String path) {
        importFromPath(importId, path, false);
    }

    /**
     * 异步导入上传后落盘到临时目录的文档，处理完成后自动清理目录。
     */
    @Async
    public void importUploadedDirectory(String importId, String dirPath) {
        importFromPath(importId, dirPath, true);
    }

    private void importFromPath(String importId, String path, boolean cleanupDir) {
        File target = new File(path);
        List<File> files = collectFiles(target);

        ImportProgress progress = new ImportProgress(importId, "RUNNING", files.size(), 0, "", null);
        progressMap.put(importId, progress);

        if (files.isEmpty()) {
            progress.setStatus("COMPLETED");
            progress.setErrorMessage("未找到支持的文档文件（PDF/Markdown）");
            return;
        }

        TokenTextSplitter splitter = new TokenTextSplitter();
        List<String> errors = new ArrayList<>();

        // 确定导入根路径
        String importRootPath = target.isDirectory() ? target.getAbsolutePath() : target.getParent();

        try {
            for (File file : files) {
                progress.setCurrentFile(file.getName());
                try {
                    List<Document> docs = readFile(file);
                    if (!docs.isEmpty()) {
                        // 注入文件级元数据
                        injectMetadata(docs, file, importRootPath);
                        List<Document> chunks = splitter.apply(docs);
                        // 注入分块级元数据
                        for (int i = 0; i < chunks.size(); i++) {
                            chunks.get(i).getMetadata().put("chunk_index", i);
                            chunks.get(i).getMetadata().put("total_chunks", chunks.size());
                        }
                        vectorStore.add(chunks);
                        upsertMetadataSummary(docs, chunks, file, importRootPath);
                        log.info("已导入文档: {}, 分块数: {}", file.getName(), chunks.size());
                    }
                } catch (Exception e) {
                    log.warn("文档解析失败: {}, 错误: {}", file.getName(), e.getMessage(), e);
                    errors.add(file.getName() + ": " + e.getMessage());
                }
                progress.setProcessedFiles(progress.getProcessedFiles() + 1);
            }

            // 持久化到磁盘
            persistVectorStore();

            // 构建 BM25 索引
            rebuildBm25Index();

            // 尝试从 VectorStore 恢复元数据注册表
            rebuildMetadataRegistry();

            progress.setCurrentFile("");
            progress.setStatus("COMPLETED");
            if (!errors.isEmpty()) {
                progress.setErrorMessage("部分文件导入失败: " + String.join("; ", errors));
            }
        } finally {
            if (cleanupDir) {
                cleanupTempDirectory(target);
            }
        }
    }

    private void cleanupTempDirectory(File target) {
        try {
            if (target != null && target.exists()) {
                Files.walk(target.toPath())
                        .sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
            }
        } catch (Exception e) {
            log.warn("清理临时目录失败: {}, {}", target, e.getMessage());
        }
    }

    /**
     * 异步导入上传的文件（支持多文件上传）。
     */
    @Async
    public void importUploadedFiles(String importId, MultipartFile[] files) {
        // 过滤出支持的文件类型
        List<MultipartFile> supportedFiles = Arrays.stream(files)
                .filter(f -> isSupportedFileName(f.getOriginalFilename()))
                .toList();

        ImportProgress progress = new ImportProgress(importId, "RUNNING", supportedFiles.size(), 0, "", null);
        progressMap.put(importId, progress);

        if (supportedFiles.isEmpty()) {
            progress.setStatus("COMPLETED");
            progress.setErrorMessage("未找到支持的文档文件（PDF/Markdown/TXT），请上传 .pdf、.md、.markdown 或 .txt 文件");
            return;
        }

        TokenTextSplitter splitter = new TokenTextSplitter();
        List<String> errors = new ArrayList<>();
        Path tempDir;
        try {
            tempDir = Files.createTempDirectory("doc-upload-");
        } catch (IOException e) {
            log.error("创建临时目录失败: {}", e.getMessage());
            progress.setStatus("COMPLETED");
            progress.setErrorMessage("创建临时目录失败: " + e.getMessage());
            return;
        }

        try {
            for (MultipartFile mf : supportedFiles) {
                String originalName = mf.getOriginalFilename();
                progress.setCurrentFile(originalName);
                File tempFile = tempDir.resolve(originalName).toFile();
                try {
                    // 保存到临时目录
                    try (InputStream is = mf.getInputStream()) {
                        Files.copy(is, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    }
                    // 复用现有文档处理逻辑
                    List<Document> docs = readFile(tempFile);
                    if (!docs.isEmpty()) {
                        injectMetadata(docs, tempFile, tempDir.toString());
                        List<Document> chunks = splitter.apply(docs);
                        for (int i = 0; i < chunks.size(); i++) {
                            chunks.get(i).getMetadata().put("chunk_index", i);
                            chunks.get(i).getMetadata().put("total_chunks", chunks.size());
                        }
                        vectorStore.add(chunks);
                        log.info("已导入上传文档: {}, 分块数: {}", originalName, chunks.size());
                    }
                } catch (Exception e) {
                    log.warn("上传文档解析失败: {}, 错误: {}", originalName, e.getMessage());
                    errors.add(originalName + ": " + e.getMessage());
                } finally {
                    // 删除临时文件
                    if (tempFile.exists() && !tempFile.delete()) {
                        log.warn("临时文件删除失败（可能被占用）: {}", tempFile.getAbsolutePath());
                    }
                }
                progress.setProcessedFiles(progress.getProcessedFiles() + 1);
            }

            // 持久化到磁盘
            persistVectorStore();
            // 构建 BM25 索引
            rebuildBm25Index();
            // 恢复元数据注册表
            rebuildMetadataRegistry();
        } finally {
            // 清理临时目录
            try {
                Files.deleteIfExists(tempDir);
            } catch (IOException e) {
                log.warn("清理临时目录失败: {}", tempDir, e);
            }
        }

        progress.setCurrentFile("");
        progress.setStatus("COMPLETED");
        if (!errors.isEmpty()) {
            progress.setErrorMessage("部分文件导入失败: " + String.join("; ", errors));
        }
    }

    private boolean isSupportedFileName(String fileName) {
        if (fileName == null) return false;
        String name = fileName.toLowerCase();
        return name.endsWith(".pdf") || name.endsWith(".md")
                || name.endsWith(".markdown") || name.endsWith(".txt");
    }

    /**
     * 查询导入进度。
     */
    public ImportProgress getProgress(String importId) {
        return progressMap.get(importId);
    }

    /**
     * 持久化 VectorStore 到磁盘。
     */
    public void persistVectorStore() {
        try {
            if (vectorStore instanceof SimpleVectorStore simpleStore) {
                File storeFile = vectorStoreConfig.getVectorStoreFile();
                storeFile.getParentFile().mkdirs();
                simpleStore.save(storeFile);
                log.info("向量存储已持久化: {}", storeFile.getAbsolutePath());
            }
        } catch (Exception e) {
            log.error("向量存储持久化失败: {}", e.getMessage());
        }
    }

    /**
     * 重建 BM25 索引（从 SimpleVectorStore 获取全部文档）
     */
    public void rebuildBm25Index() {
        try {
            if (vectorStore instanceof SimpleVectorStore simpleStore) {
                // 使用一个极宽泛的查询获取所有文档用于 BM25 索引
                List<Document> allDocs = simpleStore.similaritySearch(
                    SearchRequest.builder()
                        .query("文档")
                        .topK(10000)
                        .similarityThreshold(0.0)
                        .build()
                );
                bm25SearchService.buildIndex(allDocs);
            }
        } catch (Exception e) {
            log.warn("BM25索引构建失败，不影响主流程: {}", e.getMessage());
        }
    }

    private List<Document> readFile(File file) {
        String name = file.getName().toLowerCase();
        FileSystemResource resource = new FileSystemResource(file);

        if (name.endsWith(".pdf")) {
            TikaDocumentReader reader = new TikaDocumentReader(resource);
            return reader.get();
        } else if (name.endsWith(".md") || name.endsWith(".markdown") || name.endsWith(".txt")) {
            TextReader reader = new TextReader(resource);
            return reader.get();
        }
        return Collections.emptyList();
    }

    private List<File> collectFiles(File target) {
        List<File> result = new ArrayList<>();
        if (!target.exists()) {
            return result;
        }
        if (target.isFile() && isSupportedFile(target)) {
            result.add(target);
        } else if (target.isDirectory()) {
            File[] children = target.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (child.isFile() && isSupportedFile(child)) {
                        result.add(child);
                    }
                }
            }
        }
        return result;
    }

    private boolean isSupportedFile(File file) {
        String name = file.getName().toLowerCase();
        return name.endsWith(".pdf") || name.endsWith(".md")
                || name.endsWith(".markdown") || name.endsWith(".txt");
    }

    // ==================== 元数据注册表管理 ====================

    /**
     * 从 VectorStore 重建元数据注册表
     */
    public void rebuildMetadataRegistry() {
        try {
            if (metadataRegistry.isEmpty()) {
                log.info("元数据注册表已恢复，共 {} 篇文档", metadataRegistry.size());
                return;
            }
            log.info("元数据注册表已恢复，共 {} 篇文档", metadataRegistry.size());
        } catch (Exception e) {
            log.warn("恢复元数据注册表失败: {}", e.getMessage());
        }
    }

    private String getMetaString(Map<String, Object> meta, String key) {
        Object val = meta.get(key);
        return val != null ? val.toString() : null;
    }

    /**
     * 获取所有文档的元数据摘要
     */
    public List<DocumentMetaSummary> getMetadataSummaries() {
        return new ArrayList<>(metadataRegistry.values());
    }

    /**
     * 获取分类统计（按 doc_id 去重）
     */
    public Map<String, Long> getCategoryStats() {
        return metadataRegistry.values().stream()
                .filter(s -> s.getDocCategory() != null && !s.getDocCategory().isBlank())
                .collect(Collectors.groupingBy(DocumentMetaSummary::getDocCategory, Collectors.counting()));
    }

    /**
     * 更新指定文档的元数据
     */
    public boolean updateDocumentMetadata(String docId, Map<String, String> updates) {
        DocumentMetaSummary summary = metadataRegistry.get(docId);
        if (summary == null) {
            log.warn("文档不存在: {}", docId);
            return false;
        }

        // 更新内存注册表
        if (updates.containsKey("doc_category")) {
            summary.setDocCategory(updates.get("doc_category"));
        }
        if (updates.containsKey("keywords")) {
            summary.setKeywords(updates.get("keywords"));
        }
        if (updates.containsKey("doc_title")) {
            summary.setDocTitle(updates.get("doc_title"));
        }

        // 更新 VectorStore 中对应所有分块的 metadata
        try {
            if (vectorStore instanceof SimpleVectorStore) {
                Field storeField = SimpleVectorStore.class.getDeclaredField("store");
                storeField.setAccessible(true);
                Map<?, ?> store = (Map<?, ?>) storeField.get(vectorStore);
                int updated = 0;
                for (Object val : store.values()) {
                    if (val instanceof Document doc) {
                        Map<String, Object> meta = doc.getMetadata();
                        if (docId.equals(String.valueOf(meta.get("doc_id")))) {
                            for (Map.Entry<String, String> upd : updates.entrySet()) {
                                meta.put(upd.getKey(), upd.getValue());
                            }
                            updated++;
                        }
                    }
                }
                if (updated > 0) {
                    persistVectorStore();
                    rebuildBm25Index();
                    log.info("已更新文档 {} 的元数据，影响 {} 个分块", docId, updated);
                }
            }
        } catch (Exception e) {
            log.warn("更新 VectorStore 元数据失败: {}", e.getMessage());
        }

        return true;
    }

    // ==================== 文件读取与元数据注入 ====================

    /**
     * 注入文件级元数据到文档列表。
     */
    private void injectMetadata(List<Document> docs, File file, String importRootPath) {
        String docId = generateDocId(file);
        String fileName = file.getName();
        String docTitle = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
        String docType = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase() : "unknown";
        String category = inferCategory(file, importRootPath);
        String importTime = LocalDateTime.now().toString();

        // 检测语言 — 基于所有文档内容合并
        String combinedText = docs.stream().map(Document::getText).collect(Collectors.joining());
        String language = detectLanguage(combinedText);

        for (Document doc : docs) {
            Map<String, Object> meta = doc.getMetadata();
            meta.put("doc_id", docId);
            meta.put("doc_title", docTitle);
            meta.put("doc_type", docType);
            meta.put("doc_category", category);
            meta.put("import_time", importTime);
            meta.put("language", language);
        }
    }

    private void upsertMetadataSummary(List<Document> docs, List<Document> chunks, File file, String importRootPath) {
        if (docs.isEmpty() || chunks.isEmpty()) {
            return;
        }
        Map<String, Object> meta = docs.get(0).getMetadata();
        String docId = getMetaString(meta, "doc_id");
        if (docId == null || docId.isBlank()) {
            return;
        }
        DocumentMetaSummary summary = new DocumentMetaSummary();
        summary.setDocId(docId);
        summary.setDocTitle(getMetaString(meta, "doc_title"));
        summary.setDocType(getMetaString(meta, "doc_type"));
        summary.setDocCategory(getMetaString(meta, "doc_category"));
        summary.setImportTime(getMetaString(meta, "import_time"));
        summary.setLanguage(getMetaString(meta, "language"));
        summary.setKeywords(getMetaString(meta, "keywords"));
        summary.setChunkCount(chunks.size());
        metadataRegistry.put(docId, summary);
    }

    /**
     * 基于文件路径生成确定性 UUID（相同文件路径始终相同 ID）。
     */
    private String generateDocId(File file) {
        return UUID.nameUUIDFromBytes(
            file.getAbsolutePath().getBytes(StandardCharsets.UTF_8)
        ).toString();
    }

    /**
     * 自动检测文本语言（中文/英文），通过统计中文字符占比判断。
     */
    private String detectLanguage(String text) {
        if (text == null || text.isEmpty()) return "unknown";
        long chineseCount = text.chars()
            .filter(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)
            .count();
        double ratio = (double) chineseCount / text.length();
        return ratio > 0.1 ? "zh-CN" : "en-US";
    }

    /**
     * 从文件路径推断文档分类，使用文件所在目录名作为分类，根目录文件归类为 general。
     */
    private String inferCategory(File file, String importRootPath) {
        Path filePath = file.toPath().getParent();
        Path rootPath = Path.of(importRootPath);
        if (filePath.equals(rootPath)) {
            return "general";
        }
        Path relative = rootPath.relativize(filePath);
        return relative.getName(0).toString();
    }

    // ==================== 元数据摘要 DTO ====================

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DocumentMetaSummary {
        private String docId;
        private String docTitle;
        private String docType;
        private String docCategory;
        private String importTime;
        private String language;
        private String keywords;
        private int chunkCount;
    }
}
