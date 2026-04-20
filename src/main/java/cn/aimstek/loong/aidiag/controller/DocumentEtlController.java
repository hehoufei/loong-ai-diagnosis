package cn.aimstek.loong.aidiag.controller;

import cn.aimstek.loong.aidiag.common.BaseResponse;
import cn.aimstek.loong.aidiag.common.Response;
import cn.aimstek.loong.aidiag.config.VectorStoreConfig;
import cn.aimstek.loong.aidiag.dto.ImportProgress;
import cn.aimstek.loong.aidiag.service.DocumentEtlService;
import cn.aimstek.loong.aidiag.service.DocumentEtlService.DocumentMetaSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.List;

/**
 * 文档导入 API：触发文档导入和查询导入进度。
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v2/documents")
public class DocumentEtlController {

    private final DocumentEtlService documentEtlService;
    private final VectorStore vectorStore;
    private final VectorStoreConfig vectorStoreConfig;

    /**
     * 触发文档导入，接受本地文件或目录路径。
     */
    @PostMapping("/import")
    public Response<String> importDocs(@RequestParam String path) {
        String importId = UUID.randomUUID().toString();
        documentEtlService.importDocuments(importId, path);
        return BaseResponse.success(importId);
    }

    /**
     * 通过文件上传导入文档（支持多文件）。
     */
    @PostMapping("/upload")
    public Response<String> uploadDocs(@RequestParam("files") MultipartFile[] files) {
        String importId = UUID.randomUUID().toString();
        try {
            Path uploadDir = Files.createTempDirectory("doc-upload-controller-");
            for (MultipartFile file : files) {
                String originalName = Optional.ofNullable(file.getOriginalFilename()).orElse("unknown-file");
                File targetFile = uploadDir.resolve(originalName).toFile();
                try (InputStream is = file.getInputStream()) {
                    Files.copy(is, targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            documentEtlService.importUploadedDirectory(importId, uploadDir.toString());
        } catch (IOException e) {
            return BaseResponse.failure("UPLOAD_ERROR", "上传文件落盘失败: " + e.getMessage());
        }
        return BaseResponse.success(importId);
    }

    /**
     * 查询文档导入进度。
     */
    @GetMapping("/progress/{importId}")
    public Response<ImportProgress> getProgress(@PathVariable String importId) {
        ImportProgress progress = documentEtlService.getProgress(importId);
        if (progress == null) {
            return BaseResponse.failure("NOT_FOUND", "未找到该导入任务: " + importId);
        }
        return BaseResponse.success(progress);
    }

    /**
     * 查询知识库统计信息（文档分块数、存储文件大小）。
     */
    @GetMapping("/stats")
    public Response<Map<String, Object>> stats() {
        int chunkCount = 0;
        java.util.Set<String> sourceFiles = new java.util.TreeSet<>();
        Map<String, Long> categoryStats = new HashMap<>();
        Map<String, Long> typeStats = new HashMap<>();
        java.util.Set<String> uniqueDocIds = new java.util.HashSet<>();

        if (vectorStore instanceof SimpleVectorStore) {
            try {
                Field storeField = SimpleVectorStore.class.getDeclaredField("store");
                storeField.setAccessible(true);
                Map<?, ?> store = (Map<?, ?>) storeField.get(vectorStore);
                chunkCount = store.size();
                // 从 Document metadata 中提取文件名和分组统计
                for (Object val : store.values()) {
                    if (val instanceof org.springframework.ai.document.Document doc) {
                        Map<String, Object> meta = doc.getMetadata();

                        String source = meta.getOrDefault("source", "").toString();
                        if (!source.isBlank()) {
                            String fileName = source.replace('\\', '/');
                            int idx = fileName.lastIndexOf('/');
                            if (idx >= 0) fileName = fileName.substring(idx + 1);
                            sourceFiles.add(fileName);
                        }

                        // 按 doc_category 分组统计
                        Object cat = meta.get("doc_category");
                        if (cat != null) {
                            categoryStats.merge(cat.toString(), 1L, Long::sum);
                        }

                        // 按 doc_type 分组统计
                        Object type = meta.get("doc_type");
                        if (type != null) {
                            typeStats.merge(type.toString(), 1L, Long::sum);
                        }

                        // 收集唯一 doc_id
                        Object docId = meta.get("doc_id");
                        if (docId != null) {
                            uniqueDocIds.add(docId.toString());
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        File storeFile = vectorStoreConfig.getVectorStoreFile();
        long fileSizeKb = storeFile.exists() ? storeFile.length() / 1024 : 0;

        Map<String, Object> result = new HashMap<>();
        result.put("chunkCount", chunkCount);
        result.put("fileSizeKb", fileSizeKb);
        result.put("filePath", storeFile.getAbsolutePath());
        result.put("sourceFiles", new java.util.ArrayList<>(sourceFiles));
        result.put("categoryStats", categoryStats);
        result.put("typeStats", typeStats);
        result.put("totalDocuments", (long) uniqueDocIds.size());

        return BaseResponse.success(result);
    }

    /**
     * 列出所有文档的元数据摘要（按 doc_id 去重）
     */
    @GetMapping("/metadata")
    public Response<List<DocumentMetaSummary>> listMetadata() {
        List<DocumentMetaSummary> summaries = documentEtlService.getMetadataSummaries();
        return BaseResponse.success(summaries);
    }

    /**
     * 更新指定文档的元数据
     */
    @PutMapping("/{docId}/metadata")
    public Response<String> updateMetadata(@PathVariable String docId,
                                           @RequestBody Map<String, String> updates) {
        boolean success = documentEtlService.updateDocumentMetadata(docId, updates);
        if (success) {
            return BaseResponse.success("元数据更新成功");
        } else {
            return BaseResponse.failure("NOT_FOUND", "未找到文档: " + docId);
        }
    }

    /**
     * 获取分类统计
     */
    @GetMapping("/categories")
    public Response<Map<String, Long>> categoryStats() {
        Map<String, Long> stats = documentEtlService.getCategoryStats();
        return BaseResponse.success(stats);
    }
}
