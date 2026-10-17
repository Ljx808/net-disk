package backned.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 回收站列表里的一行。
 * <p>
 * 和 {@link FileItemVO} 分开而不是给它加个可空字段：两个列表展示的信息本来就不同，
 * 回收站多一个"删除时间"，文件列表不需要。共用会逼前端判断"这个字段在当前页面有没有意义"。
 */
@Data
public class RecycleItemVO {

    /** user_file 的 id，恢复/彻底删除时要用它 */
    private Long id;

    private String filename;

    private Long fileSize;

    /**
     * 移入回收站的时间。
     * <p>
     * 取的是 {@code user_file.update_time}：软删除是一次 UPDATE，
     * MySQL 的 {@code ON UPDATE CURRENT_TIMESTAMP} 会自动把它刷成删除那一刻。
     * 不单独加 delete_time 列，因为回收站状态只有一个时间戳，加列反而多一份要维护的数据。
     */
    private LocalDateTime deleteTime;
}
