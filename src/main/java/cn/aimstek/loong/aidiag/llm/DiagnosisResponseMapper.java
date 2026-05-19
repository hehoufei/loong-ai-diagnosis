package cn.aimstek.loong.aidiag.llm;

import cn.aimstek.loong.aidiag.dto.DiagnoseResponse;
import cn.aimstek.loong.aidiag.dto.RootCauseItem;
import cn.aimstek.loong.aidiag.exception.AiDiagnosisException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 解析 LLM 原始输出为 DiagnoseResponse。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiagnosisResponseMapper {

    private final ObjectMapper objectMapper;

    public DiagnoseResponse fromLlmOutput(String llmOutput) {
        try {
            String cleaned = clean(llmOutput);
            JsonNode root = objectMapper.readTree(cleaned);

            DiagnoseResponse response = new DiagnoseResponse();
            response.setSummary(root.path("summary").asText(""));

            List<RootCauseItem> items = new ArrayList<>();
            JsonNode rootCauses = root.path("rootCauses");
            if (rootCauses.isArray()) {
                for (JsonNode node : rootCauses) {
                    items.add(new RootCauseItem(
                            node.path("title").asText(""),
                            node.path("description").asText("")
                    ));
                }
            }
            response.setRootCauses(items);

            List<String> actions = new ArrayList<>();
            JsonNode actionNode = root.path("actions");
            if (actionNode.isArray()) {
                for (JsonNode node : actionNode) {
                    actions.add(node.asText(""));
                }
            }
            response.setActions(actions);

            if (root.path("confidence").isNumber()) {
                response.setConfidence(root.path("confidence").asDouble());
            }

            return response;
        } catch (Exception e) {
            log.error("解析 LLM 输出失败, output={}", llmOutput, e);
            throw new AiDiagnosisException("LLM_RESPONSE_PARSE_ERROR", "LLM 输出解析失败", e);
        }
    }

    private String clean(String llmOutput) {
        if (llmOutput == null) {
            throw new AiDiagnosisException("LLM_RESPONSE_EMPTY", "LLM 输出为空");
        }
        String cleaned = llmOutput.strip();
        // 去除 ```json ... ``` 代码块标记
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            if (firstNewline > 0) {
                cleaned = cleaned.substring(firstNewline + 1);
            }
            if (cleaned.endsWith("```")) {
                cleaned = cleaned.substring(0, cleaned.length() - 3);
            }
            cleaned = cleaned.strip();
        }
        return cleaned;
    }
}
