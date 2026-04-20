package cn.aimstek.loong.aidiag.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文档导入进度
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ImportProgress {

    private String importId;

    /** 状态: RUNNING | COMPLETED | FAILED */
    private String status;

    private int totalFiles;

    private int processedFiles;

    private String currentFile;

    private String errorMessage;
}
