package backned.mapper;

import backned.entity.UserFile;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
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
     * 该语义已于 Day 8 定案并写入 CLAUDE.md：软删除/恢复不动 ref_count，
     * 只有彻底删除 user_file 行时才递减，所以这里"数全部行"与 ref_count 始终可比。
     * <p>
     * 返回 Map 而不是实体：这是一次聚合查询，没有对应的表实体；
     * 两个 key 分别是 realFileId 和 cnt（SQL 里起的别名）。
     */
    @Select("SELECT real_file_id AS realFileId, COUNT(*) AS cnt FROM user_file GROUP BY real_file_id")
    List<Map<String, Object>> countByRealFileId();

    /**
     * 统计一批文件路径各自被多少条 user_file 引用（清理前的第二道闸）。
     * <p>
     * user_file 没有路径字段，路径挂在 real_file 上，所以要 JOIN 一次。
     * 同样不过滤 is_delete：回收站里的行也算引用。
     * <p>
     * 一次查完而不是循环单查（避免 N+1）：清理请求通常只有几条路径，
     * 但"批量接口不该按条查库"这个习惯要从一开始就养成。
     *
     * @param filePaths 待检查的相对路径（调用方保证非空）
     * @return 每行 {filePath, cnt}，没有被引用的路径不会出现在结果里
     */
    @Select("""
            <script>
            SELECT rf.file_path AS filePath, COUNT(*) AS cnt
            FROM user_file uf
            JOIN real_file rf ON rf.id = uf.real_file_id
            WHERE rf.file_path IN
            <foreach collection="filePaths" item="p" open="(" separator="," close=")">#{p}</foreach>
            GROUP BY rf.file_path
            </script>
            """)
    List<Map<String, Object>> countReferencesByPaths(@Param("filePaths") List<String> filePaths);
}
