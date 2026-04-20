package cn.aimstek.loong.aidiag.config;

import cn.aimstek.loong.aidiag.service.BM25SearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * VectorStore 配置：使用 SimpleVectorStore（JSON 文件持久化），
 * 桌面端无需外部向量数据库。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class VectorStoreConfig {

    private final AgentProperties agentProperties;

    @Autowired(required = false)
    private BM25SearchService bm25SearchService;

    @Bean
    public VectorStore vectorStore(EmbeddingModel embeddingModel) {
        SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();
        File storeFile = getVectorStoreFile();

        if (storeFile.exists()) {
            try {
                store.load(storeFile);
                log.info("已从文件加载向量存储: {}", storeFile.getAbsolutePath());
                // 初始化 BM25 索引
                initBm25Index(store);
            } catch (Exception e) {
                log.error("向量存储文件损坏，将备份旧文件并创建新存储: {}", e.getMessage());
                backupCorruptedFile(storeFile);
                // store 保持空状态即可
            }
        } else {
            log.info("向量存储文件不存在，将使用空存储: {}", storeFile.getAbsolutePath());
            // 确保父目录存在
            storeFile.getParentFile().mkdirs();
        }

        return store;
    }

    /**
     * 获取向量存储持久化文件路径。
     * 优先使用 AgentProperties.vectorStorePath 配置，
     * 否则 Windows 下使用 %APPDATA%/wcs-diagnosis/vectorstore/vector-store.json，
     * 其他系统使用 ~/.wcs-diagnosis/vectorstore/vector-store.json。
     */
    public File getVectorStoreFile() {
        String configPath = agentProperties.getVectorStorePath();
        if (configPath != null && !configPath.isBlank()) {
            return new File(configPath);
        }

        String baseDir;
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            // Windows
            baseDir = appData + File.separator + "wcs-diagnosis";
        } else {
            // Linux / macOS
            baseDir = System.getProperty("user.home") + File.separator + ".wcs-diagnosis";
        }

        return new File(baseDir + File.separator + "vectorstore" + File.separator + "vector-store.json");
    }

    private void backupCorruptedFile(File file) {
        try {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
            File backup = new File(file.getParent(), "vector-store-corrupted-" + timestamp + ".json");
            Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            log.info("已备份损坏的向量存储文件: {}", backup.getAbsolutePath());
        } catch (IOException e) {
            log.warn("备份损坏文件失败: {}", e.getMessage());
        }
    }

    /**
     * 初始化 BM25 索引：从已加载的 VectorStore 中获取文档
     */
    private void initBm25Index(SimpleVectorStore store) {
        if (bm25SearchService == null) return;
        try {
            List<Document> allDocs = store.similaritySearch(
                SearchRequest.builder()
                    .query("文档")
                    .topK(10000)
                    .similarityThreshold(0.0)
                    .build()
            );
            bm25SearchService.buildIndex(allDocs);
        } catch (Exception e) {
            log.warn("初始化BM25索引失败: {}", e.getMessage());
        }
    }

}
