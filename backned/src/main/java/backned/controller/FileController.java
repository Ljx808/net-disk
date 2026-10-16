package backned.controller;

import backned.common.Result;
import backned.service.FileService;
import backned.vo.DownloadFileVO;
import backned.vo.FileItemVO;
import backned.vo.FileUploadVO;
import backned.vo.PageVO;
import backned.vo.UploadConfigVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 文件接口。
 * <p>
 * 注意这个 controller 里没有任何地方从参数里取 userId：
 * 当前用户是谁，由拦截器解析 token 后放进 ThreadLocal，service 里自己拿。
 * 前端传什么 userId 都不作数。
 */
@Slf4j
@RestController
@RequestMapping("/api/file")
@RequiredArgsConstructor
public class FileController {

    private final FileService fileService;

    /**
     * 上传单个文件。
     * 请求必须是 multipart/form-data，表单字段名固定为 file（要和前端 FormData.append('file', ...) 对上）。
     */
    @PostMapping("/upload")
    public Result<FileUploadVO> upload(@RequestParam("file") MultipartFile file) {
        return Result.success("上传成功", fileService.upload(file));
    }

    /**
     * 分页查询当前用户的文件列表。
     * <p>
     * 分页参数走 URL query（GET /api/file/list?pageNum=1&pageSize=10），而不是请求体：
     * ① "查询"语义就该用 GET，能被浏览器地址栏、curl 直接调试；
     * ② 不用 MyBatis-Plus 的 Page 对象当入参，是因为 Page 带着 orders、searchCount 之类的内部字段，
     *    让前端能传这些等于把 ORM 的实现细节暴露到接口上。
     * <p>
     * defaultValue 让前端不传参数也能拿到第 1 页，传了非法值（0、负数、超大）由 service 夹紧。
     */
    @GetMapping("/list")
    public Result<PageVO<FileItemVO>> list(
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "10") long pageSize) {
        return Result.success(fileService.listMine(pageNum, pageSize));
    }

    /**
     * 上传配置。前端拿它显示"单文件最大 xxx"，并在选文件时先做一次前置校验，
     * 不用等传完 100MB 才发现超限。限制值的唯一来源是 application.yml。
     */
    @GetMapping("/config")
    public Result<UploadConfigVO> config() {
        return Result.success(fileService.getUploadConfig());
    }

    /**
     * 下载文件。
     * <p>
     * 这个接口和别的不一样：它的响应体是文件二进制，不是 Result 包起来的 JSON。
     * 所以返回类型是 ResponseEntity&lt;Resource&gt; 而不是 Result&lt;T&gt;。
     * <p>
     * 一个字节的文件内容都不会在这里被读进内存：service 只负责校验和拿句柄，
     * 真正的拷贝由 Spring 的 ResourceHttpMessageConverter 在返回之后用 8KB 缓冲区
     * 边读边写进响应输出流。文件 1GB 也不会导致堆内存增长 1GB。
     */
    @GetMapping("/download/{userFileId}")
    public ResponseEntity<Resource> download(@PathVariable Long userFileId) {
        // 归属校验（含 is_delete）、文件是否存在，都在这里做完；
        // 校验不过会抛 BusinessException，由 GlobalExceptionHandler 转成 404 + JSON
        DownloadFileVO file = fileService.download(userFileId);

        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .contentType(resolveMediaType(file.getMimeType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, buildContentDisposition(file.getFilename()))
                // attachment + nosniff 是两道防线，防止"上传一个 .html 再用浏览器打开"变成存储型 XSS
                .header("X-Content-Type-Options", "nosniff");

        // Content-Length 取磁盘上文件的真实大小（resource.contentLength()），
        // 不用 real_file.file_size：万一两者不一致，以磁盘为准才不会让浏览器
        // 收到的字节数和声明的不符。拿不到大小就不设这个头（宁可没有进度条，也不要一个错的长度）
        long contentLength = contentLength(file.getResource());
        if (contentLength >= 0) {
            response.contentLength(contentLength);
        }
        return response.body(file.getResource());
    }

    /**
     * 把库里存的 mime_type 转成 Content-Type。
     * <p>
     * 必须先校验再用：mime_type 是上传时客户端自报的值，直接塞进响应头的话，
     * 一个乱写的字符串就能让整个响应头格式坏掉（Spring 会拿它去解析 MediaType）。
     */
    private MediaType resolveMediaType(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            return MediaType.parseMediaType(mimeType);
        } catch (InvalidMediaTypeException e) {
            log.warn("mime_type 无法解析，回落为 octet-stream: {}", mimeType);
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    /**
     * 生成 Content-Disposition 头。
     * <p>
     * 中文等非 ASCII 文件名必须用 RFC 5987 的 {@code filename*=UTF-8''<百分号编码>} 参数，
     * 同时保留一个 ASCII 的 {@code filename=} 给老客户端兜底 ——
     * Spring 的 ContentDisposition builder 会一次生成这两个参数。
     * <p>
     * 顺带说下手写的坑：如果自己用 URLEncoder.encode 做这个编码，它会把手写的空格编成 {@code +}，
     * 而 RFC 5987 规定空格必须是 {@code %20}，所以必须再 {@code .replace("+", "%20")}，
     * 漏了这一步，文件名里带空格就会变成加号。
     */
    private String buildContentDisposition(String filename) {
        // 再剥一遍控制字符：库里可能还有 Day4 那版没做这道过滤时存进来的文件名。
        // 文件名是用户输入的，带着 \r\n 就能往响应里注入额外的头（响应头注入）
        String safe = filename == null ? "" : filename.replaceAll("\\p{Cntrl}", "");
        if (safe.isBlank()) {
            safe = "download";
        }
        return ContentDisposition.attachment()
                .filename(safe, StandardCharsets.UTF_8)
                .build()
                .toString();
    }

    /** 返回 -1 表示拿不到大小 */
    private long contentLength(Resource resource) {
        try {
            return resource.contentLength();
        } catch (IOException e) {
            // 拿不到文件大小不是致命错误：不设 Content-Length 浏览器照样能下载，
            // 只是看不到进度条。为这个把请求打成 500 不值得 —— 文件本身是好的
            log.warn("读取文件大小失败，本次响应不带 Content-Length: {}", resource.getDescription(), e);
            return -1;
        }
    }
}
