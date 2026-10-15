package backned.common;

import lombok.Getter;

/**
 * 业务异常。
 * <p>
 * service 层遇到"用户名已存在"这类可预期的错误时直接抛它，
 * 由 GlobalExceptionHandler 统一转成 Result，controller 里不用写 try-catch。
 * 继承 RuntimeException（非受检异常），这样不会污染方法签名。
 */
@Getter
public class BusinessException extends RuntimeException {

    private final Integer code;

    public BusinessException(String message) {
        this(500, message);
    }

    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
    }
}
