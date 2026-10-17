package backned.common;

import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.TypeMismatchException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.stream.Collectors;

/**
 * 全局异常处理。
 * <p>
 * {@code @RestControllerAdvice} = {@code @ControllerAdvice} + {@code @ResponseBody}，
 * 相当于给所有 controller 包了一层"统一 catch"，异常在这里被翻译成 Result。
 * <p>
 * <b>为什么继承 {@link ResponseEntityExceptionHandler}</b>：
 * 这个父类已经为 Spring MVC 自己会抛的一整族框架异常（URL 不存在、方法不支持、
 * Content-Type 不支持、请求体读不出来、少了必需参数/部件、参数校验失败……）
 * 写好了 {@code @ExceptionHandler}，并且按每个异常的语义给出正确的 HTTP 状态码，
 * 最后统一回调我们覆写的 {@link #handleExceptionInternal}。
 * <p>
 * 之前是自己一个个枚举这些异常来处理的，那种写法天然会漏 —— Day 6 就漏掉了
 * {@code MissingServletRequestPartException}（挂着上传接口却不带 file 部件），
 * 结果"调用方传错了"又变回"系统繁忙"。交给父类接管后，这一族不用再枚举。
 * <p>
 * <b>编码规则（两条线，别混）</b>：
 * <ul>
 *   <li>业务异常 {@link BusinessException}：带自己的细粒度业务码（401、40401、40402…），
 *       其中 404xx 映射成真正的 HTTP 404、409xx 映射成真正的 HTTP 409，
 *       其余维持 HTTP 200（历史行为，见 {@link #toHttpStatus}）。</li>
 *   <li>框架异常：HTTP 状态码是它自己的语义（400/404/405/415/500），业务码统一取
 *       {@code HTTP 状态码 × 100}（404 → 40400、400 → 40000），前端看 code 的前三位
 *       就知道对应的 HTTP 语义，不用再一张张记编号。</li>
 * </ul>
 * <p>
 * <b>实施要点（两个坑都是实测踩出来的，别凭直觉）</b>：
 * <ul>
 *   <li>父类那个 {@code final handleException(Exception, WebRequest)} 上挂的不是 {@code Exception.class}，
 *       而是 {@code @ExceptionHandler({...20 个具体类型...})}（HttpRequestMethodNotSupportedException、
 *       NoResourceFoundException、MethodArgumentNotValidException、MaxUploadSizeExceededException…）。
 *       结论一：**这些类型不能在本类里再写 {@code @ExceptionHandler}**，否则启动即抛
 *       "Ambiguous @ExceptionHandler method mapped for [...]"，想改提示只能**覆写同签名的 protected 钩子**。</li>
 *   <li>结论二：{@code Exception.class} 父类**不管**，所以本类必须自己留一个兜底 ——
 *       否则意外异常（NullPointerException、数据库连不上…）会绕过本类，走 Spring Boot 默认的
 *       {@code /error}，响应结构就不再是 Result 信封了。</li>
 * </ul>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    /** 业务码 404xx 的区间：文件不存在 / 无权访问 / 内容缺失，这类要映射成真正的 HTTP 404 */
    private static final int NOT_FOUND_CODE_START = 40400;
    private static final int NOT_FOUND_CODE_END = 40500;

    /** 业务码 409xx 的区间：资源状态冲突（如恢复时同目录已有同名文件），映射成真正的 HTTP 409 */
    private static final int CONFLICT_CODE_START = 40900;
    private static final int CONFLICT_CODE_END = 41000;

    /** 上传上限的原始文案（如 100MB），从 application.yml 读，不写死 */
    @Value("${spring.servlet.multipart.max-file-size}")
    private String maxFileSize;

    // ========================================================================
    // 一、业务异常：保留细粒度业务码
    // ========================================================================

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
     * 目前映射两个区间：404xx → 404、409xx → 409；其余一律维持 HTTP 200。
     * 维持 HTTP 200 是有意的保守做法，不是在偷懒：401/400 目前的表现是
     * HTTP 200 + body 里的 code，前端拦截器就是按这个行为写的（比如 401 会触发跳登录页），
     * 那些接口 Day4/Day5 已经验收过了。
     * 代价是"HTTP 状态码和业务码不完全一致"，已记入 CLAUDE.md 的已知技术债。
     * <p>
     * 409xx 是 Day8 新增的区间：恢复文件时同目录已有同名文件属于"请求与当前资源状态冲突"，
     * 用 409 比 400（请求本身格式有错）更准确。区间映射沿用 404xx 的写法，
     * 以后新增业务码只要落进区间就自动拿到正确状态码，不用再改这里。
     */
    private HttpStatus toHttpStatus(Integer code) {
        if (code == null) {
            return HttpStatus.OK;
        }
        if (code >= NOT_FOUND_CODE_START && code < NOT_FOUND_CODE_END) {
            return HttpStatus.NOT_FOUND;
        }
        if (code >= CONFLICT_CODE_START && code < CONFLICT_CODE_END) {
            return HttpStatus.CONFLICT;
        }
        return HttpStatus.OK;
    }

    // ========================================================================
    // 二、父类没管的两类异常：MultipartException（父类只管子类 MaxUploadSizeExceededException）
    //     和 Exception 本身（父类那 20 个具体类型之外的一切）
    // ========================================================================

    /**
     * 上传文件超过大小限制。
     * <p>
     * 提示里的上限值从配置读，不写死字符串 —— 否则改了 application.yml 却忘了改这里，提示就是错的。
     * <p>
     * 注意这里是**覆写父类的钩子**而不是写 {@code @ExceptionHandler(MaxUploadSizeExceededException.class)}：
     * 父类已经把这个类型登记在它那个 {@code final handleException} 的映射里了，再声明一遍就是
     * "Ambiguous @ExceptionHandler method mapped for [MaxUploadSizeExceededException]"，
     * 应用直接起不来（这个错实测踩到了，不是理论风险）。父类把"接管哪些异常"和"怎么处理"分开设计：
     * 映射写死在 final 方法里不让动，处理逻辑放在这些 protected 钩子里让你覆写。
     * <p>
     * 状态码强制写 400：文件太大是调用方的问题，不能因为父类传进来的状态码变化就退化成"系统繁忙"。
     */
    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(
            MaxUploadSizeExceededException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {

        return handleExceptionInternal(ex,
                Result.error(HttpStatus.BAD_REQUEST.value() * 100, "文件太大，单个文件最大 " + maxFileSize),
                headers, HttpStatus.BAD_REQUEST, request);
    }

    /**
     * multipart 解析失败，最常见的是"拿 JSON 打上传接口"：
     * Content-Type 不是 multipart/form-data，Spring 会报 "Current request is not a multipart request"。
     * 这也是调用方发错了，不是服务端故障。
     * <p>
     * 这个类型父类**没有**接管（它只管子类 {@code MaxUploadSizeExceededException}），
     * 所以可以放心声明具体的 handler；反过来，文件超限会优先命中父类登记的子类型、
     * 走上面的 {@link #handleMaxUploadSizeExceededException}，不会被这里的泛化提示盖掉。
     */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Object> handleMultipart(MultipartException e, WebRequest request) {
        return handleExceptionInternal(e,
                Result.error(HttpStatus.BAD_REQUEST.value() * 100,
                        "请求不是合法的文件上传格式（需要 multipart/form-data）"),
                new HttpHeaders(), HttpStatus.BAD_REQUEST, request);
    }

    /**
     * 兜底：前面所有分支都没接住的意外异常（NullPointerException、数据库连接失败……）。
     * <p>
     * 这一条不能省。父类的 {@code @ExceptionHandler} 上是一串具体类型，**不含 Exception 本身**，
     * 所以不写这个兜底的话，意外异常会绕过本类走 Spring Boot 默认的 {@code /error}，
     * 前端拿到的就不是 {@code {code, message, data}} 了，拦截器会解析失败、只弹一句"请求失败(500)"。
     * <p>
     * 对外只说一句通用提示，堆栈写日志（{@link #handleExceptionInternal} 里对 5xx 记 error），
     * 避免把内部细节透给前端。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpectedException(Exception e, WebRequest request) {
        return handleExceptionInternal(e, null, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
    }

    // ========================================================================
    // 三、覆写父类的钩子：只为把提示换成更有用的中文
    // ========================================================================

    /**
     * 参数校验失败（{@code @Valid} 不通过）。
     * <p>
     * 覆写父类是为了保住字段级提示：父类只会给一句通用的 "Validation failed"，
     * 而 {@code @NotBlank(message = "用户名不能为空")} 里写好的中文提示才是给用户看的。
     * 多个字段同时不合法时用分号拼起来。
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {

        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getDefaultMessage())
                .collect(Collectors.joining("；"));

        Integer code = (message == null || message.isBlank()) ? null : status.value() * 100;
        return handleExceptionInternal(ex,
                code == null ? null : Result.error(code, message),
                headers, status, request);
    }

    /**
     * 参数类型不对，比如 GET /api/file/list?pageNum=abc。
     * <p>
     * 覆写父类是为了说清楚"哪个参数"错了：父类只给通用提示。
     * {@code MethodArgumentTypeMismatchException} 是 {@code TypeMismatchException} 的子类，
     * 只有它能拿到参数名，所以这里用 instanceof 取一下。
     */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {

        String name = (ex instanceof MethodArgumentTypeMismatchException mismatch) ? mismatch.getName() : null;
        String message = (name == null || name.isBlank()) ? null : "参数 " + name + " 格式不正确";
        return handleExceptionInternal(ex,
                message == null ? null : Result.error(status.value() * 100, message),
                headers, status, request);
    }

    // ========================================================================
    // 四、统一出口：所有框架异常都从这里出去，包成 Result 信封
    // ========================================================================

    /**
     * 框架异常的统一出口。
     * <p>
     * 父类的每个 {@code handleXxx} 钩子最后都会回调到这里，所以只要在这一处包信封，
     * "整族框架异常都返回 Result 结构"这件事就自动成立，不用每个异常写一遍。
     *
     * @param body 可能是我们自己放进去的 {@link Result}（上面几个覆写方法），
     *             也可能是父类生成的 {@link ProblemDetail}（英文细节，只写日志、不回传给前端）
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {

        // 客户端提前断开（最典型的是下载中途关掉页面）时响应已经提交，再写响应体只会抛二次异常。
        // 父类自己也是这么判断的，这里保留同样的守卫。
        if (request instanceof ServletWebRequest servletWebRequest) {
            HttpServletResponse response = servletWebRequest.getResponse();
            if (response != null && response.isCommitted()) {
                log.warn("响应已提交，无法再写入错误信息: {}", ex.getMessage());
                return null;
            }
        }

        int code = statusCode.value() * 100;
        if (statusCode.is5xxServerError()) {
            // 5xx 才是真的服务端故障，打全堆栈
            log.error("系统异常: status={}, code={}", statusCode.value(), code, ex);
        } else {
            // 4xx 是调用方发错了，只记一行，并把父类生成的英文细节（如 "Required part 'file' is not present."）记进日志
            log.warn("请求不合法: status={}, code={}, message={}, detail={}",
                    statusCode.value(), code, ex.getMessage(),
                    (body instanceof ProblemDetail problemDetail) ? problemDetail.getDetail() : null);
        }

        // 我们自己给的 Result 直接用；父类给的 ProblemDetail（或其他）一律换成中文通用提示，
        // 避免把英文细节和内部信息透给前端
        Object envelope = (body instanceof Result<?> result) ? result
                : Result.error(code, defaultMessage(statusCode));

        // 新建一份 header，不要把父类塞进来的 Content-Type: application/problem+json 一起带出去
        return new ResponseEntity<>(envelope, new HttpHeaders(), statusCode);
    }

    /** 按 HTTP 语义给一句中文提示。细节在日志里，这里只说人话 */
    private String defaultMessage(HttpStatusCode statusCode) {
        return switch (statusCode.value()) {
            case 400 -> "请求参数或请求体不合法";
            case 404 -> "接口不存在";
            case 405 -> "请求方法不支持";
            case 406 -> "无法生成客户端要求的内容类型";
            case 415 -> "请求类型不支持";
            default -> statusCode.is4xxClientError() ? "请求不合法" : "系统繁忙，请稍后重试";
        };
    }
}
