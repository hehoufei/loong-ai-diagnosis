package cn.aimstek.loong.aidiag.exception;

import lombok.Getter;

@Getter
public class AiDiagnosisException extends RuntimeException {

    private final String errorCode;
    private final String userMessage;

    public AiDiagnosisException(String errorCode, String userMessage) {
        super(userMessage);
        this.errorCode = errorCode;
        this.userMessage = userMessage;
    }

    public AiDiagnosisException(String errorCode, String userMessage, Throwable cause) {
        super(userMessage, cause);
        this.errorCode = errorCode;
        this.userMessage = userMessage;
    }
}
