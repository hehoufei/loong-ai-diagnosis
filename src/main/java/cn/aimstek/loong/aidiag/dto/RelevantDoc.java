package cn.aimstek.loong.aidiag.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RelevantDoc {
    private String source;   // 来源文件名（如 "美欣达-WCS异常场景及SOP.pdf"）
    private String content;  // 文档内容摘要
}
