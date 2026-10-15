package backned.common;

import lombok.Getter;

/**
 * 统一返回结构，所有接口都返回这个格式，前端只需判断 code。
 *
 * @param <T> 业务数据类型
 */
@Getter
public class Result<T> {

    /** 200 表示成功，其他都表示失败（400 参数错误 / 401 未登录 / 500 业务或系统异常） */
    private final Integer code;
    private final String message;
    private final T data;

    private Result(Integer code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> Result<T> success(T data) {
        return new Result<>(200, "操作成功", data);
    }

    public static <T> Result<T> success(String message, T data) {
        return new Result<>(200, message, data);
    }

    public static <T> Result<T> error(String message) {
        return new Result<>(500, message, null);
    }

    public static <T> Result<T> error(Integer code, String message) {
        return new Result<>(code, message, null);
    }
}
