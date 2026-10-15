package backned.controller;

import backned.common.Result;
import backned.dto.LoginDTO;
import backned.dto.RegisterDTO;
import backned.service.UserService;
import backned.utils.UserContext;
import backned.vo.UserInfoVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户接口。
 * <p>
 * controller 只做三件事：接参数、调 service、包 Result。
 * 业务规则（查重、加密、签发 token）都在 service 里，这样以后写单元测试也不用起 Web 容器。
 */
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /** 注册。@Valid 触发 DTO 上的校验注解，不通过会抛异常交给全局异常处理器 */
    @PostMapping("/register")
    public Result<Void> register(@RequestBody @Valid RegisterDTO dto) {
        userService.register(dto);
        // 注册成功不返回任何敏感数据，前端跳转到登录页即可
        return Result.success("注册成功", null);
    }

    /** 登录，成功时 data 就是 token */
    @PostMapping("/login")
    public Result<String> login(@RequestBody @Valid LoginDTO dto) {
        return Result.success("登录成功", userService.login(dto));
    }

    /**
     * 查询当前登录用户信息。
     * 这里没有 userId 入参——用户身份只能来自 token（拦截器已解析好放进 UserContext），
     * 如果让前端传 userId，别人就能随便改数字查别人的资料了。
     */
    @GetMapping("/info")
    public Result<UserInfoVO> info() {
        return Result.success(userService.getInfo(UserContext.getUserId()));
    }
}
