package backned.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.stream.Collectors;

/**
 * 全局异常处理。
 * <p>
 * @RestControllerAdvice = @ControllerAdvice + @ResponseBody，
 * 相当于给所有 controller 包了一层"统一 catch"，异常在这里被翻译成 Result。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常：属于可预期错误，只记 warn，不打印堆栈 */
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException e) {
        log.warn("业务异常: {}", e.getMessage());
        return Result.error(e.getCode(), e.getMessage());
    }

    /**
     * 参数校验异常。
     * 当 @RequestBody 上的 @Valid 校验不通过时，Spring 抛的就是这个异常。
     * 多个字段同时不合法时会把提示用分号拼起来返回。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getDefaultMessage())
                .collect(Collectors.joining("；"));
        log.warn("参数校验失败: {}", message);
        return Result.error(400, message);
    }

    /**
     * 参数类型不对，比如 GET /api/file/list?pageNum=abc。
     * <p>
     * 不单独处理的话会掉进最下面的兜底分支，返回"系统繁忙"——
     * 把调用方传错参数说成服务端故障，排查问题时会被彻底带偏。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("参数类型不匹配: name={}, value={}", e.getName(), e.getValue());
        return Result.error(400, "参数 " + e.getName() + " 格式不正确");
    }

    /**
     * 上传文件超过大小限制。
     * 不单独处理的话会掉进下面的兜底分支，返回"系统繁忙"，用户完全不知道是文件太大。
     * <p>
     * 提示里的限制值直接从配置读，不写死字符串 ——
     * 否则改了 application.yml 的 max-file-size 却忘了改这里的文案，提示就是错的。
     */
    @Value("${spring.servlet.multipart.max-file-size}")
    private String maxFileSize;

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Result<Void> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        log.warn("上传文件超过大小限制: {}", e.getMessage());
        return Result.error(400, "文件太大，单个文件最大 " + maxFileSize);
    }

    /** 兜底：前面都没接住的异常，对外只暴露一句通用提示，细节写日志，避免把堆栈泄露给前端 */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("系统异常", e);
        return Result.error(500, "系统繁忙，请稍后重试");
    }
}
