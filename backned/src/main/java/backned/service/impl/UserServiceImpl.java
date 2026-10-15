package backned.service.impl;

import backned.common.BusinessException;
import backned.dto.LoginDTO;
import backned.dto.RegisterDTO;
import backned.entity.User;
import backned.mapper.UserMapper;
import backned.service.UserService;
import backned.utils.JwtUtils;
import backned.vo.UserInfoVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 用户业务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final JwtUtils jwtUtils;

    /**
     * BCrypt 加密器。
     * <p>
     * BCrypt 是"单向哈希"，不可逆，数据库里存的永远是密文；
     * 它每次加密会自动生成随机盐并混进结果里，所以同一个密码每次加密结果都不同，
     * 校验只能用 matches(明文, 密文)，不能把明文加密后去比对字符串。
     * 这个对象是无状态的、线程安全的，做成字段复用即可。
     */
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Override
    public void register(RegisterDTO dto) {
        // 1. 先查重，能给用户友好提示（也顺便挡住绝大多数重复提交）
        Long count = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getUsername, dto.getUsername()));
        if (count > 0) {
            throw new BusinessException("用户名已存在");
        }

        // 2. 密码加密后入库，明文全程不落库、不打日志
        User user = new User();
        user.setUsername(dto.getUsername());
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        user.setEmail(dto.getEmail());
        // storageLimit / createTime / updateTime 全部留 null：
        // MyBatis-Plus 默认的字段策略会把 null 字段从 INSERT 语句里剔除，
        // 让 MySQL 的 DEFAULT 值(1GB、当前时间)生效，不用在代码里硬编码。

        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            // 3. 兜底并发场景：两个请求同时通过上面的查重，最终只能有一个插入成功，
            //    另一个会被 username 唯一索引拦下来。这是查重 + 唯一索引的"双保险"。
            log.warn("并发注册冲突，用户名: {}", dto.getUsername());
            throw new BusinessException("用户名已存在");
        }
        log.info("用户注册成功，id={}, username={}", user.getId(), user.getUsername());
    }

    @Override
    public String login(LoginDTO dto) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, dto.getUsername()));

        // 用户不存在和密码错误返回同一句提示，避免被人拿来做"用户名是否存在"的探测
        // 注意 user 为 null 时要短路，不能先调用 getPassword()，否则空指针
        if (user == null || !passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            throw new BusinessException("用户名或密码错误");
        }

        // 登录成功签发 token，后续请求带着它就能证明"我是谁"
        return jwtUtils.generateToken(user.getId());
    }

    @Override
    public UserInfoVO getInfo(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            // 能走到这里说明 token 合法但用户已被删除
            throw new BusinessException("用户不存在");
        }
        return UserInfoVO.from(user);
    }
}
