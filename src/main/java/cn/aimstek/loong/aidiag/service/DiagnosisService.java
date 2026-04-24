package cn.aimstek.loong.aidiag.service;

import cn.aimstek.loong.aidiag.dto.DiagnoseRequest;
import cn.aimstek.loong.aidiag.dto.DiagnoseResponse;
import cn.aimstek.loong.aidiag.exception.AiDiagnosisException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * @deprecated 旧诊断入口已收敛到 {@link DiagnosisFacade}。
 * 该类保留为兼容层，后续可在确认所有调用方迁移完成后再移除。
 */
@Deprecated
@Service
@Primary
@RequiredArgsConstructor
public class DiagnosisService {

    private final DiagnosisFacade diagnosisFacade;

    @Deprecated
    public DiagnoseResponse diagnose(DiagnoseRequest request) {
        validate(request);
        return diagnosisFacade.diagnose(request);
    }

    private void validate(DiagnoseRequest request) {
        if (request == null || !StringUtils.hasText(request.getTaskId())) {
            throw new AiDiagnosisException("VALIDATION_ERROR", "taskId不能为空", null);
        }
    }

    /*
     * 旧实现已迁移到 DiagnosisFacade / DiagnosisContextAssembler / DiagnosisResponseMapper。
     * 原始逻辑保留在 Git 历史中，避免在当前文件继续维护双入口与重复逻辑。
     */
}
