package cn.aimstek.loong.aidiag.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PointConflict {
    private String lockValue;
    private String lockType;
    private String occupiedBy;
    private LocalDateTime expirationTime;
}
