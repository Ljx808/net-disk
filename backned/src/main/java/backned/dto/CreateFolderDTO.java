package backned.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新建文件夹请求参数。
 * <p>
 * 名字的合法性全部交给注解校验，失败时由 {@code @Valid} + 全局异常处理统一返回
 * HTTP 400 + 40000，service 里不再重复写一套判断（只做 trim 和同目录重名检查）。
 */
@Data
public class CreateFolderDTO {

    /** 父目录 id，0 或不传表示根目录 */
    private Long parentId;

    @NotBlank(message = "文件夹名不能为空")
    @Size(max = 200, message = "文件夹名不能超过 200 个字符")
    // 整名等于 "." 或 ".." 才拒绝；"my.folder" 这类带点的正常名字必须放行
    @Pattern(regexp = "^(?!\\.{1,2}$).*$", message = "文件夹名不能是 . 或 ..")
    // 任意位置出现 / 或 \ 都拒绝：目录名不允许带路径分隔符
    @Pattern(regexp = "^[^/\\\\]*$", message = "文件夹名不能包含 / 或 \\")
    // 控制字符（\r \n 等）一律拒绝，避免展示与日志注入
    @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "文件夹名不能包含控制字符")
    private String foldername;
}
