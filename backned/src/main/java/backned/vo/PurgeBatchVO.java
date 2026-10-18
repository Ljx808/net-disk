package backned.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 清空回收站的批量结果。
 * <p>
 * 每一条都单独走一次"彻底删除"，所以允许部分成功：
 * 比如某条正被另一个请求恢复，就记为 failed，其余照常删完。
 * 前端按 items 逐条展示即可。
 */
@Data
@Builder
public class PurgeBatchVO {

    /** 服务端实际圈定的回收站条数（不是前端传的） */
    private int requestedCount;

    private int deletedCount;

    private int failedCount;

    private List<PurgeItemVO> items;
}
