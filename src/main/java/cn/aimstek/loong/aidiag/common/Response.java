package cn.aimstek.loong.aidiag.common;

import lombok.Data;

@Data
public class Response<T> {
    private boolean success;
    private String code;
    private String message;
    private T data;
}
