package cn.aimstek.loong.aidiag.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class DiagnoseResponse {
    private String summary;
    private List<RootCauseItem> rootCauses = new ArrayList<>();
    private List<String> actions = new ArrayList<>();
    private List<RelevantDoc> relevantDocs = new ArrayList<>();
}
