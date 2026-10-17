package backned.service;

import backned.vo.ReconcileReportVO;

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
}
