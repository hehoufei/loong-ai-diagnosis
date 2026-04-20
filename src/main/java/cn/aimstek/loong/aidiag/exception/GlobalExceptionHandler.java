package cn.aimstek.loong.aidiag.exception;

import cn.aimstek.loong.aidiag.common.BaseResponse;
import cn.aimstek.loong.aidiag.common.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AiDiagnosisException.class)
    public Response<Object> handleAiDiagnosisException(AiDiagnosisException ex) {
        log.warn("AI诊断异常, code={}, msg={}", ex.getErrorCode(), ex.getUserMessage());
        return BaseResponse.failure(ex.getErrorCode(), ex.getUserMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Response<Object> handleIllegalArgumentException(IllegalArgumentException ex) {
        return BaseResponse.failure("INVALID_PARAM", ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public Response<Object> handleException(Exception ex) {
        log.error("系统异常", ex);
        String detail = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
        return BaseResponse.failure("SYSTEM_ERROR", "系统异常: " + detail);
    }
}
