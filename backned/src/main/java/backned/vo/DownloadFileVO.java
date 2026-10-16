package backned.vo;

import lombok.Data;
import org.springframework.core.io.Resource;

/**
 * 下载时 service 交给 controller 的信息。
 * <p>
 * 注意它和别的 VO 不一样：它不参与 JSON 序列化、不会作为响应体返回，
 * 只是 service → controller 之间的一次性载体，所以可以放 Resource 这种对象。
 * <p>
 * 故意没有 fileSize 字段：响应头的 Content-Length 直接取 {@code resource.contentLength()}
 * （磁盘上文件的真实字节数），不用 real_file.file_size。
 * 万一哪天磁盘文件和数据库记录对不上，以磁盘为准，Content-Length 才不会
 * 和实际发出去的字节数不一致 —— 后者会让浏览器收到的文件被截断或卡在 99%。
 */
@Data
public class DownloadFileVO {

    /** user_file.filename，用户上传时的原始文件名，也是下载时呈现给用户的名字 */
    private String filename;

    /** real_file.mime_type，可能为 null，也可能是客户端上传时乱报的值，用之前必须校验 */
    private String mimeType;

    /** 文件句柄，只持有引用，不含内容 */
    private Resource resource;
}
