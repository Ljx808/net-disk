package backend.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 手动清理报告。
 * <p>
 * 清理是<b>逐条独立</b>的：一条被跳过不影响其他条目，
 * 所以响应必须带逐条结果，而不是只给一个总数。
 */
@Data
@Builder
public class CleanupReportVO {

    /** 本次清理时间 */
    private String cleanedAt;

    /** 请求里提交的路径条数（含重复和空串） */
    private int requested;

    private int deletedCount;

    private int skippedCount;

    /** 逐条结果，顺序与请求顺序一致，便于前端/人直接对照 */
    private List<Item> items;

    @Data
    @Builder
    public static class Item {
        /** 请求里的原始路径（原样回显，不做规范化，方便排查） */
        private String relativePath;

        /** deleted / skipped */
        private String status;

        /** skipped 时的原因；deleted 时为 null */
        private String reason;

        /** 以下元信息在 deleted 时回填，便于事后审计删了什么 */
        private Long sizeBytes;
        private String lastModified;
        private Long ageHours;
    }
}
