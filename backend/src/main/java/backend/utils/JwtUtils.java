package backend.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具类：负责生成 token 和从 token 里解析出 userId。
 * <p>
 * JWT 由三部分组成（用 . 分隔）：Header.Payload.Signature。
 * 前两段只是 Base64 编码、人人都能解开看，所以千万别往里放密码；
 * 第三段是用密钥做的签名，别人改一个字符签名就对不上，因此能防篡改。
 * 服务端不保存 token（无状态），这正是不用 session 的好处。
 */
@Slf4j
@Component
public class JwtUtils {

    @Value("${jwt.secret}")
    private String secret;

    /** 过期时间，单位毫秒 */
    @Value("${jwt.expire}")
    private Long expire;

    private SecretKey key;

    /**
     * 启动时就把密钥构建好，避免每次请求都重复计算。
     * 顺便校验长度：HS256 要求密钥至少 256 位(32 字节)，太短会直接抛异常，
     * 在这里给出明确报错，比运行到一半才失败好排查。
     */
    @PostConstruct
    public void init() {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("jwt.secret 至少需要 32 个字节(HS256 要求 256 位)，当前只有 " + bytes.length + " 字节");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        log.info("JwtUtils 初始化完成，token 有效期 {} 毫秒", expire);
    }

    /**
     * 生成 token。
     * subject 放 userId —— 不放 username，因为 username 理论上可改，而 id 永远不变。
     */
    public String generateToken(Long userId) {
        Date now = new Date();
        Date expireAt = new Date(now.getTime() + expire);
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .issuedAt(now)
                .expiration(expireAt)
                .signWith(key)
                .compact();
    }

    /**
     * 解析 token 取出 userId。
     * 签名不合法、被篡改或已过期，jjwt 都会抛 JwtException，由拦截器捕获后返回 401。
     */
    public Long parseUserId(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return Long.valueOf(claims.getSubject());
    }
}
