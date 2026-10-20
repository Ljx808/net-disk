package backend.service;

import backend.vo.CleanupReportVO;
import backend.vo.ReconcileReportVO;

import java.util.List;

/**
 * 对账巡检。
 * <p>
 * 这个服务是<b>纯只读</b>的：只做三次 SELECT 和一次目录扫描，
 * 不写库、不改文件、不修任何差异。发现的问题原样进报告，由人决定怎么处理。
 */
public interface ReconcileService {

    /**
     * 执行一次全量对账。
     *
     * @return 三段报告（盘有库无 / 库有盘无 / ref_count 审计）
     */
    ReconcileReportVO reconcile();

    /**
     * 手动清理孤儿文件（唯一会删磁盘文件的接口）。
     * <p>
     * 不信任调用方给的任何路径：白名单在每个请求开始时用<b>实时扫描 + 实时查库</b>重算，
     * 只有既是盘上文件、又没有任何 real_file 记录的路径才可能进入删除流程。
     * 另外还要过两道闸：无 user_file 引用（含回收站）、落盘满 24 小时。
     *
     * @param paths 显式提交的相对路径（与 real_file.file_path 同口径，正斜杠分隔）
     * @return 逐条结果 + 汇总
     */
    CleanupReportVO cleanup(List<String> paths);
}
