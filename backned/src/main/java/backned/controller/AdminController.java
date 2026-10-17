package backned.controller;

import backned.common.Result;
import backned.service.ReconcileService;
import backned.vo.ReconcileReportVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理/运维接口。
 * <p>
 * 登录保护由现有 JWT 拦截器带来：它拦 {@code /api/**}，只放行注册和登录，
 * 所以 {@code /api/admin/**} 天然需要 token，无需额外配置。
 * <p>
 * 注意：目前只校验"登录了"，不校验"是不是管理员"（项目还没有角色体系），
 * 这是已知技术债。好在当前唯一的接口是只读巡检，不会改数据。
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final ReconcileService reconcileService;

    /**
     * 对账巡检：只报告，不修复。
     * <p>
     * 刻意做成 GET：它不改任何数据，语义上就是一次查询，
     * 可以安全地重复调用、也可以直接在浏览器地址栏里带 token 调试。
     */
    @GetMapping("/reconcile")
    public Result<ReconcileReportVO> reconcile() {
        return Result.success(reconcileService.reconcile());
    }
}
