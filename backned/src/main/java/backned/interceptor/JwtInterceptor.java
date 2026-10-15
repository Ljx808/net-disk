package backned.interceptor;

import backned.utils.JwtUtils;
import backned.utils.UserContext;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * JWT 登录拦截器：进入 controller 之前先验一遍身份。
 * <p>
 * preHandle 返回 true 表示放行，返回 false 表示拦下（此时必须自己把响应写完）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtInterceptor implements HandlerInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtUtils jwtUtils;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        // 静态资源、跨域预检(OPTIONS)请求等不是 HandlerMethod，直接放行
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || header.isBlank()) {
            writeUnauthorized(response, "未登录，请先登录");
            return false;
        }

        // 兼容两种写法：带 "Bearer " 前缀的（标准）和裸 token（方便 curl/Postman 调试）
        String token = header.startsWith(BEARER_PREFIX) ? header.substring(BEARER_PREFIX.length()) : header;

        try {
            // 解析成功说明签名有效且没过期，把 userId 存进当前线程
            UserContext.setUserId(jwtUtils.parseUserId(token));
            return true;
        } catch (ExpiredJwtException e) {
            writeUnauthorized(response, "登录已过期，请重新登录");
            return false;
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("token 校验失败: {}", e.getMessage());
            writeUnauthorized(response, "token 无效，请重新登录");
            return false;
        }
    }

    /**
     * 请求处理结束后一定要清理 ThreadLocal。
     * 拦截器抛异常、controller 报错都会走到这里，所以放 afterCompletion 最稳妥。
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContext.clear();
    }

    /**
     * 拦截器里抛异常不会经过 @RestControllerAdvice，所以手动写响应体，
     * 保证前端拿到的依然是 Result 结构，不用为 401 单独写一套解析逻辑。
     */
    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"message\":\"" + message + "\",\"data\":null}");
    }
}
