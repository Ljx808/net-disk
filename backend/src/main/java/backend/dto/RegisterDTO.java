package backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 注册请求参数。
 * <p>
 * DTO（Data Transfer Object）专门用来接前端的入参，
 * 和数据库实体 User 分开的好处：前端多传/少传字段不会影响表结构，参数校验注解也不会污染实体。
 * 注解由 controller 上的 @Valid 触发，校验失败由 GlobalExceptionHandler 统一处理。
 */
@Data
public class RegisterDTO {

    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 20, message = "用户名长度必须为3-20位")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 20, message = "密码长度必须为6-20位")
    private String password;

    /** 邮箱可选，表里允许为空；填了才校验格式 */
    @Email(message = "邮箱格式不正确")
    private String email;
}
