package backned.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文件列表里的一行。
 * <p>
 * 和 FileUploadVO 一样，故意不返回这些字段：
 * <ul>
 *   <li>file_path：磁盘路径是服务端内部实现，前端拿着也没法访问，暴露出去反而是信息泄露；</li>
 *   <li>real_file_id、md5：物理文件的标识，属于内部实现细节。后续做下载/删除用 user_file 的 id 就够了。</li>
 * </ul>
 */
@Data
public class FileItemVO {

    /** user_file 的 id，前端做下载/删除时要用它当参数 */
    private Long id;

    private String filename;

    /** 字节数。换算成 KB/MB 是展示层的事，接口只给原始值，避免精度在传输中丢失。文件夹固定为 0 */
    private Long fileSize;

    /** true=文件夹，false=文件。前端据此决定点进去还是下载 */
    private Boolean isFolder;

    /** 上传时间，Jackson 默认序列化成 ISO-8601 字符串（2026-10-17T10:30:00） */
    private LocalDateTime createTime;
}
