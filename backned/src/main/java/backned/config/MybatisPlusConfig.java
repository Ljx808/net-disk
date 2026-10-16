package backned.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件配置。
 * <p>
 * 这个类只干一件事：把分页插件注册进去。
 * <p>
 * <b>不注册会怎样？</b>不会报错，但 selectPage() 是"假分页"——
 * 它会把整张表查出来，只在返回的对象里截了一段给你，total 还是 0。
 * 前端就会看到"共 0 条"但明明有数据，而且数据量一大直接 OOM。
 * 这是 MyBatis-Plus 最常见的一个坑：分页不是自动的，必须显式注册插件。
 */
@Configuration
public class MybatisPlusConfig {

    /** 单页最大条数，和 FileServiceImpl 里的夹紧逻辑保持一致，属于双重保险 */
    private static final long MAX_PAGE_SIZE = 100L;

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        // 即使某个接口忘了夹紧 pageSize，插件层也会兜住，最多只查 100 条
        pagination.setMaxLimit(MAX_PAGE_SIZE);
        interceptor.addInnerInterceptor(pagination);

        return interceptor;
    }
}
