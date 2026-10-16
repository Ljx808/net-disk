package backned.controller;

import backned.common.Result;
import backned.service.FileService;
import backned.vo.FileUploadVO;
import lombok.RequiredArgsConstructor;
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
}
