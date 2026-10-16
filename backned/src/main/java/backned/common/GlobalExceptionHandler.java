package backned.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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

    /** 业务码 404xx 的区间：文件不存在 / 无权访问 / 内容缺失，这类要映射成真正的 HTTP 404 */
    private static final int NOT_FOUND_CODE_START = 40400;
    private static final int NOT_FOUND_CODE_END = 40500;

    /**
     * 业务异常：属于可预期错误，只记 warn，不打印堆栈。
     * <p>
     * 返回值是 ResponseEntity 而不是 Result，因为这里要能控制 HTTP 状态码：
     * Day6 的下载接口要求"文件不存在"返回真正的 404，而不是 HTTP 200 + body 里写个 404。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BusinessException e) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        return ResponseEntity.status(toHttpStatus(e.getCode()))
                .body(Result.error(e.getCode(), e.getMessage()));
    }

    /**
     * 业务码 → HTTP 状态码。
     * <p>
     * 只映射 404xx，其余一律维持 HTTP 200 —— 这是有意的保守做法，不是在偷懒：
     * 401/400 目前的表现是 HTTP 200 + body 里的 code，前端拦截器就是按这个行为写的
     * （比如 401 会触发跳登录页），那些接口 Day4/Day5 已经验收过了，不在 Day6 的改动范围内。
     * 代价是"HTTP 状态码和业务码不完全一致"，已记入 CLAUDE.md 的已知技术债。
     */
    private HttpStatus toHttpStatus(Integer code) {
        if (code != null && code >= NOT_FOUND_CODE_START && code < NOT_FOUND_CODE_END) {
            return HttpStatus.NOT_FOUND;
        }
        return HttpStatus.OK;
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

    /**
     * multipart 解析失败，最常见的是"拿 JSON 打上传接口"：
     * Content-Type 不是 multipart/form-data，Spring 会报 "Current request is not a multipart request"。
     * 这也是调用方发错了，不是服务端故障，不能掉进兜底分支。
     * <p>
     * 注意这里有个父子类陷阱：MaxUploadSizeExceededException 是 MultipartException 的子类，
     * 两个处理器都能接住"文件超限"那一种异常。Spring 选的是**继承链上最具体**的那个
     * （见 ExceptionDepthComparator），所以超限依然走上面的分支、提示"文件太大"，
     * 不会被这里的泛化提示盖掉。这一点靠实测确认过，不是想当然。
     */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Result<Void>> handleMultipart(MultipartException e) {
        log.warn("multipart 请求解析失败: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.error(40002, "请求不是合法的文件上传格式（需要 multipart/form-data）"));
    }

    /**
     * 请求的 URL 在应用里没有对应的接口。
     * <p>
     * Spring 6.1 起，找不到处理器时抛的是 {@link NoResourceFoundException}（而不是静默 404）。
     * 它本身带着"404"这个明确语义，但如果没人接住，就会掉进最下面的兜底分支，
     * 变成"HTTP 200 + code:500 系统繁忙"——把"你 URL 打错了"说成"服务端故障"。
     * <p>
     * 这不是假想的坑：Day 6 的下载功能在旧构建的实例上就是靠这个假 500 掩盖了
     * "接口根本没加载"这个真相，排查时被彻底带偏，所以单独接住。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<Void>> handleNoResourceFound(NoResourceFoundException e) {
        log.warn("接口不存在: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Result.error(40400, "接口不存在"));
    }

    /** 请求方法不对，比如拿 GET 打上传接口 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("请求方法不支持: method={}, supported={}", e.getMethod(), e.getSupportedHttpMethods());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(Result.error(40500, "请求方法不支持: " + e.getMethod()));
    }

    /** Content-Type 不是接口能吃的，比如上传接口收到 application/json */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
        log.warn("请求媒体类型不支持: contentType={}", e.getContentType());
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(Result.error(41500, "请求类型不支持: " + e.getContentType()));
    }

    /**
     * 请求体读不出来，最常见的是 @RequestBody 的 JSON 语法错了（少括号、多逗号）。
     * 这也是"调用方传错"而不是"服务端故障"，同样不能让它掉进兜底分支。
     * 注意提示里不回显原始报文，避免把用户传的内容原样吐回去。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Result.error(40001, "请求体格式不正确"));
    }

    /** 兜底：前面都没接住的异常，对外只暴露一句通用提示，细节写日志，避免把堆栈泄露给前端 */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("系统异常", e);
        return Result.error(500, "系统繁忙，请稍后重试");
    }
}
