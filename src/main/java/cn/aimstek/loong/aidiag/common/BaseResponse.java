package cn.aimstek.loong.aidiag.common;

public class BaseResponse {

    public static <T> Response<T> success(T data) {
        Response<T> response = new Response<>();
        response.setSuccess(true);
        response.setCode("SUCCESS");
        response.setMessage("ok");
        response.setData(data);
        return response;
    }

    public static <T> Response<T> failure(String code, String message) {
        Response<T> response = new Response<>();
        response.setSuccess(false);
        response.setCode(code);
        response.setMessage(message);
        return response;
    }
}
