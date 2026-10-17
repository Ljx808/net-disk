package backned.mapper;

import backned.entity.UserFile;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface UserFileMapper extends BaseMapper<UserFile> {

    /**
     * 按 real_file_id 统计实际被引用的行数（对账巡检用）。
     * <p>
     * <b>口径</b>：不过滤 is_delete —— 回收站里的行（is_delete=1）也算引用，
     * 因为它的物理文件必须还在，用户随时可能恢复。
     * Day 8 做删除功能时会正式定义这个语义（届时若改为"回收站不算引用"，
     * 这里和 ref_count 的维护逻辑要同步改）。
     * <p>
     * 返回 Map 而不是实体：这是一次聚合查询，没有对应的表实体；
     * 两个 key 分别是 realFileId 和 cnt（SQL 里起的别名）。
     */
    @Select("SELECT real_file_id AS realFileId, COUNT(*) AS cnt FROM user_file GROUP BY real_file_id")
    List<Map<String, Object>> countByRealFileId();
}
