package backend.vo;

import lombok.Data;

/**
 * 上传相关的配置，给前端做展示和前置校验用。
 * <p>
 * 存在的意义：单文件大小上限只应该有一个来源，就是 application.yml。
 * 前端在页面上写死"最大 100MB"，改了 yml 之后这句话就是错的，
 * 所以让前端启动时问一次后端。
 */
@Data
public class UploadConfigVO {

    /** 单文件最大字节数，来自 spring.servlet.multipart.max-file-size */
    private Long maxFileSize;
}
