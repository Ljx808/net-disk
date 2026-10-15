package backned.config;

import backned.interceptor.JwtInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层配置：注册拦截器。
 * <p>
 * WebMvcConfigurer 是 Spring 提供的扩展点，实现它并重写对应方法就能定制 MVC 行为，
 * 不会覆盖掉 Spring Boot 的自动配置（这点比继承 WebMvcConfigurationSupport 友好得多）。
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final JwtInterceptor jwtInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtInterceptor)
                // 默认拦所有 /api 接口：以后新增的接口自动受保护，不会因为忘记加拦截而裸奔
                .addPathPatterns("/api/**")
                // 只有注册和登录不需要登录态——这是登录接口本身的鸡生蛋问题
                .excludePathPatterns("/api/user/register", "/api/user/login");
    }
}
