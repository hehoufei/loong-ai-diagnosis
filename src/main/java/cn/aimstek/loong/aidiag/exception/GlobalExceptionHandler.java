package cn.aimstek.loong.aidiag.exception;

import cn.aimstek.loong.aidiag.common.BaseResponse;
import cn.aimstek.loong.aidiag.common.Response;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AiDiagnosisException.class)
    public Response<Object> handleAiDiagnosisException(AiDiagnosisException ex, HttpServletRequest request) {
        if (isSseRequest(request)) {
            log.warn("SSE 请求发生AI诊断异常, code={}, msg={}", ex.getErrorCode(), ex.getUserMessage());
            return null;
        }
        log.warn("AI诊断异常, code={}, msg={}", ex.getErrorCode(), ex.getUserMessage());
        return BaseResponse.failure(ex.getErrorCode(), ex.getUserMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Response<Object> handleIllegalArgumentException(IllegalArgumentException ex, HttpServletRequest request) {
        if (isSseRequest(request)) {
            log.warn("SSE 请求发生参数异常: {}", ex.getMessage());
            return null;
        }
        return BaseResponse.failure("INVALID_PARAM", ex.getMessage());
    }

    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public Response<Object> handleAsyncRequestTimeoutException(AsyncRequestTimeoutException ex, HttpServletRequest request) {
        if (isSseRequest(request)) {
            log.warn("SSE 请求超时, path={}", request.getRequestURI());
            return null;
        }
        log.error("异步请求超时", ex);
        return BaseResponse.failure("REQUEST_TIMEOUT", "请求超时，请重试");
    }

    @ExceptionHandler(Exception.class)
    public Response<Object> handleException(Exception ex, HttpServletRequest request) {
        if (isSseRequest(request)) {
            log.error("SSE 请求系统异常", ex);
            return null;
        }
        log.error("系统异常", ex);
        String detail = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
        return BaseResponse.failure("SYSTEM_ERROR", "系统异常: " + detail);
    }
    private boolean isSseRequest(HttpServletRequest request) {
        if (request == null) {
            return false;
        }
        String uri = request.getRequestURI();
        String accept = request.getHeader("Accept");
        return (uri != null && uri.contains("/api/v2/diagnosis/chat"))
                || (accept != null && accept.contains("text/event-stream"));
    }
}
