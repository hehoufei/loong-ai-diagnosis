package cn.aimstek.loong.aidiag.service;

import cn.aimstek.loong.aidiag.dto.DiagnoseResponse;
import cn.aimstek.loong.aidiag.dto.RelevantDoc;
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
 * 诊断结果映射与归一化。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiagnosisResponseMapper {

    private final ObjectMapper objectMapper;

    public DiagnoseResponse normalize(DiagnoseResponse response) {
        if (response == null) {
            return new DiagnoseResponse();
        }
        if (response.getRootCauses() == null) {
            response.setRootCauses(new ArrayList<>());
        }
        if (response.getActions() == null) {
            response.setActions(new ArrayList<>());
        }
        if (response.getRelevantDocs() == null) {
            response.setRelevantDocs(new ArrayList<>());
        }
        if (response.getDiagnosisMode() == null || response.getDiagnosisMode().isBlank()) {
            response.setDiagnosisMode("fallback");
        }
        if (response.getAnalysisScope() == null || response.getAnalysisScope().isBlank()) {
            response.setAnalysisScope("single");
        }
        if (response.getConfidence() == null) {
            response.setConfidence(0.0);
        }
        return response;
    }

    public DiagnoseResponse withDocs(DiagnoseResponse response, List<String> docs) {
        DiagnoseResponse normalized = normalize(response);
        if (docs == null || docs.isEmpty()) {
            return normalized;
        }
        List<RelevantDoc> relevantDocs = new ArrayList<>(normalized.getRelevantDocs());
        for (String doc : docs) {
            if (doc == null || doc.isBlank()) {
                continue;
            }
            String source = "";
            String content = doc;
            if (doc.startsWith("[") && doc.contains("] ")) {
                int endBracket = doc.indexOf("] ");
                source = doc.substring(1, endBracket);
                content = doc.substring(endBracket + 2);
            }
            relevantDocs.add(new RelevantDoc(source, content));
        }
        normalized.setRelevantDocs(relevantDocs);
        return normalized;
    }

    public DiagnoseResponse fromLlmOutput(String llmOutput) {
        try {
            String cleaned = clean(llmOutput);
            JsonNode root = objectMapper.readTree(cleaned);

            DiagnoseResponse response = new DiagnoseResponse();
            response.setSummary(root.path("summary").asText(""));
            response.setAnalysisScope(root.path("analysisScope").asText("single"));
            response.setDiagnosisMode(root.path("diagnosisMode").asText("llm"));
            response.setConfidence(root.path("confidence").isNumber() ? root.path("confidence").asDouble() : 0.0);
            response.setTraceId(root.path("traceId").asText(""));

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
            return normalize(response);
        } catch (Exception e) {
            log.error("解析LLM输出失败, output={}", llmOutput, e);
            throw new AiDiagnosisException("LLM_RESPONSE_PARSE_ERROR", "LLM输出解析失败，请稍后重试", e);
        }
    }

    private String clean(String llmOutput) {
        if (llmOutput == null) {
            throw new AiDiagnosisException("LLM_RESPONSE_EMPTY", "LLM输出为空", null);
        }
        String cleaned = llmOutput.strip();
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
