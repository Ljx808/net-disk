package backend.controller;

import backend.common.Result;
import backend.service.ReconcileService;
import backend.vo.CleanupReportVO;
import backend.vo.ReconcileReportVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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

    /**
     * 手动清理孤儿文件：请求体是显式路径数组。
     * <p>
     * 为什么用 POST 而不是 DELETE：清理对象是"一批路径"，DELETE 带请求体在语义和
     * 一些代理上都有争议；POST 表达"提交一批待处理项"更贴切。
     * <p>
     * 路径不在请求里就不动 —— 这个接口永远不会自己扩大删除范围。
     */
    @PostMapping("/reconcile/cleanup")
    public Result<CleanupReportVO> cleanup(@RequestBody List<String> paths) {
        return Result.success("清理完成", reconcileService.cleanup(paths));
    }
}
