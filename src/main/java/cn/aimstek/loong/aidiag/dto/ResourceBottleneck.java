package cn.aimstek.loong.aidiag.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResourceBottleneck {
    private String resourceType;
    private String resourceKey;
    private String description;
    private Integer impactedTaskCount;
}
