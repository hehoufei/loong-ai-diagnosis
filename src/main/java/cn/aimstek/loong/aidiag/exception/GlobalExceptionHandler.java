package cn.aimstek.loong.aidiag.exception;

import cn.aimstek.loong.aidiag.common.BaseResponse;
import cn.aimstek.loong.aidiag.common.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器。
 * 错误码到 HTTP 状态码的映射：
 * - VALIDATION_ERROR       → 400
 * - TASK_NOT_FOUND         → 404
 * - PLATFORM_UNAVAILABLE   → 503
 * - 其他                   → 500
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AiDiagnosisException.class)
    public ResponseEntity<Response<Object>> handleAiDiagnosisException(AiDiagnosisException ex) {
        HttpStatus status = mapErrorCodeToStatus(ex.getErrorCode());
        log.warn("诊断异常 code={} status={} msg={}", ex.getErrorCode(), status.value(), ex.getUserMessage());
        Response<Object> body = BaseResponse.failure(ex.getErrorCode(), ex.getUserMessage());
        return ResponseEntity.status(status).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Response<Object>> handleValidationException(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .orElse("参数校验失败");
        log.warn("参数校验失败: {}", message);
        Response<Object> body = BaseResponse.failure("VALIDATION_ERROR", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Response<Object>> handleIllegalArgumentException(IllegalArgumentException ex) {
        log.warn("非法参数: {}", ex.getMessage());
        Response<Object> body = BaseResponse.failure("INVALID_PARAM", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Response<Object>> handleException(Exception ex) {
        log.error("系统异常", ex);
        String detail = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
        Response<Object> body = BaseResponse.failure("SYSTEM_ERROR", "系统异常: " + detail);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    private HttpStatus mapErrorCodeToStatus(String errorCode) {
        if (errorCode == null) return HttpStatus.INTERNAL_SERVER_ERROR;
        return switch (errorCode) {
            case "VALIDATION_ERROR", "INVALID_PARAM" -> HttpStatus.BAD_REQUEST;
            case "TASK_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "PLATFORM_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
            case "LLM_RATE_LIMIT" -> HttpStatus.TOO_MANY_REQUESTS;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
