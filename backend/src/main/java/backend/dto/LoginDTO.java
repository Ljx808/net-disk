package backend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 登录请求参数。
 * <p>
 * 这里只校验非空，不校验长度：登录时限制长度没有意义，
 * 而且如果规则和注册不一致，反而会让老用户输入正确密码却报"格式错误"。
 * 非空校验只是为了拦住空请求，避免无谓地查一次库。
 */
@Data
public class LoginDTO {

    @NotBlank(message = "用户名不能为空")
    private String username;

    @NotBlank(message = "密码不能为空")
    private String password;
}
