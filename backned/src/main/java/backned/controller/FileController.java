package backned.controller;

import backned.common.Result;
import backned.service.FileService;
import backned.vo.FileItemVO;
import backned.vo.FileUploadVO;
import backned.vo.PageVO;
import backned.vo.UploadConfigVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件接口。
 * <p>
 * 注意这个 controller 里没有任何地方从参数里取 userId：
 * 当前用户是谁，由拦截器解析 token 后放进 ThreadLocal，service 里自己拿。
 * 前端传什么 userId 都不作数。
 */
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
}
