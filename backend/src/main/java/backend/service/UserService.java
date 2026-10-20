package backend.service;

import backend.dto.LoginDTO;
import backend.dto.RegisterDTO;
import backend.vo.UserInfoVO;

/**
 * 用户业务接口。
 * <p>
 * 定义接口而不是直接写类，是为了让 controller 依赖抽象（面向接口编程），
 * 以后想换实现（比如加缓存）不影响调用方。小项目里也能让"这个模块对外提供什么能力"一目了然。
 */
public interface UserService {

    /** 注册，用户名重复时抛 BusinessException */
    void register(RegisterDTO dto);

    /** 登录成功返回 JWT，失败抛 BusinessException */
    String login(LoginDTO dto);

    /** 按 id 查用户信息（不含密码） */
    UserInfoVO getInfo(Long userId);
}
