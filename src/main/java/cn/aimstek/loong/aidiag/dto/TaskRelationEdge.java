package cn.aimstek.loong.aidiag.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskRelationEdge {
    private String fromTaskId;
    private String toTaskId;
    private String relationType;
    private String resourceKey;
    private String description;
}
