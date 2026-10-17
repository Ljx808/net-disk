package backned.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 对账巡检报告（只读，不改任何数据）。
 * <p>
 * 三段报告对应三种"账实不符"：
 * <ul>
 *   <li>{@link #orphans} 盘有库无：磁盘上的文件没有任何 real_file 记录引用，通常是上传中途失败的残留；</li>
 *   <li>{@link #missing} 库有盘无：库里有记录但磁盘上找不到文件，用户下载会失败；</li>
 *   <li>{@link #refCountDiffs} ref_count 审计：库里的引用计数和 user_file 实际行数对不上。</li>
 * </ul>
 * 三个明细类做成静态嵌套类，而不是各占一个文件：它们只服务于这一份报告，
 * 拆出去会让 vo 包膨胀，也让"这三段属于同一次扫描"这层关系变模糊。
 */
@Data
@Builder
public class ReconcileReportVO {

    /** 本次扫描时间（本地时区） */
    private String scannedAt;

    /** 被扫描的存储根目录，报告里写出来便于确认扫的是不是同一个地方 */
    private String storageRoot;

    /** 磁盘上的文件总数 */
    private int diskFileCount;

    /** real_file 行数 */
    private int realFileCount;

    /** 三项统计汇总，前端看这一层就够，明细按需展开 */
    private Summary summary;

    /** 盘有库无（孤儿候选），按相对路径升序 */
    private List<Orphan> orphans;

    /** 库有盘无（失踪文件），按 real_file.id 升序 */
    private List<Missing> missing;

    /** ref_count 与实际引用数不一致的记录，按 real_file.id 升序；一致的不会出现在这里 */
    private List<RefCountDiff> refCountDiffs;

    /** 审计口径说明，避免读报告的人误解"回收站行算不算引用" */
    private String refCountNote;

    /** 已知局限说明：并发上传窗口、以及哪些检查不在本次范围 */
    private String caveat;

    @Data
    @Builder
    public static class Summary {
        private int orphanCount;
        private int missingCount;
        private int refCountMismatchCount;

        /** 本次参与 ref_count 审计的 real_file 行数 */
        private int refCountChecked;
    }

    @Data
    @Builder
    public static class Orphan {
        /** 相对存储根目录的路径，正斜杠分隔，与 real_file.file_path 同口径 */
        private String relativePath;
        private long sizeBytes;

        /** 落盘时间（文件最后修改时间） */
        private String lastModified;

        /** 年龄（天，向下取整）。清理前必须满足"早于 N 天"，所以这个字段是安全阈值 */
        private long ageDays;
    }

    @Data
    @Builder
    public static class Missing {
        private Long realFileId;

        /** 库里记录的文件路径（原样返回，不做美化，方便直接拿去排查） */
        private String filePath;
    }

    @Data
    @Builder
    public static class RefCountDiff {
        private Long realFileId;
        private String filePath;

        /** real_file.ref_count 里存的值 */
        private Integer dbRefCount;

        /** user_file 里实际引用它的行数（含 is_delete=1） */
        private Long actualRefCount;
    }
}
