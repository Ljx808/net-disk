package backned.vo;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.Data;

import java.util.List;
import java.util.function.Function;

/**
 * 通用分页返回结构。
 * <p>
 * 为什么不直接把 MyBatis-Plus 的 {@code Page} 返回给前端：
 * Page 里除了数据还带着 orders、optimizeCountSql、searchCount、countId 等一堆 ORM 内部字段，
 * 返回出去等于把持久层实现暴露给前端；而且将来换掉 MyBatis-Plus，前端就得跟着改。
 * 加一层 VO 只透出前端真正需要的 5 个字段，两边就解耦了。
 *
 * @param <T> 列表里每一行的类型
 */
@Data
public class PageVO<T> {

    /** 当前页的数据 */
    private List<T> records;

    /** 满足条件的总条数（不是本页条数），前端分页组件要靠它算总页数 */
    private Long total;

    private Long pageNum;

    private Long pageSize;

    /** 总页数。后端算好给前端，省得两边各算一遍、算法还不一致 */
    private Long pages;

    /**
     * 把 MyBatis-Plus 的分页对象搬进这个 VO。
     *
     * @param page   插件填充好的分页对象
     * @param mapper 实体 → VO 的转换函数，由调用方决定怎么转
     */
    public static <E, T> PageVO<T> of(IPage<E> page, Function<E, T> mapper) {
        PageVO<T> vo = new PageVO<>();
        vo.setRecords(page.getRecords().stream().map(mapper).toList());
        vo.setTotal(page.getTotal());
        vo.setPageNum(page.getCurrent());
        vo.setPageSize(page.getSize());
        vo.setPages(page.getPages());
        return vo;
    }
}
