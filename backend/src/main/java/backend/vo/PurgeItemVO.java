package backend.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 彻底删除一条回收站记录的结果。
 * <p>
 * 为什么要把"物理文件删没删"告诉调用方：删除共享文件时物理文件必须保留，
 * 这里用 {@code shared=true / physicalDeleted=false} 明确表达，前端不会误以为出了故障。
 */
@Data
@Builder
public class PurgeItemVO {

    /** user_file 的 id */
    private Long id;

    private String filename;

    /** 递减后的引用数；失败时为 null */
    private Integer refCountAfter;

    /** 物理文件是否仍被其他记录共享（refCountAfter &gt; 0 即视为共享，本次不删盘） */
    private boolean shared;

    /** 本次是否真的删掉了物理文件（共享保留、或文件本来就不存在时为 false） */
    private boolean physicalDeleted;

    /** deleted / failed */
    private String status;

    /** 失败原因；成功时为 null */
    private String reason;
}
