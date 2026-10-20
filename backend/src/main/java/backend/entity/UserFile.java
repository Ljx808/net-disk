package backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户文件：用户在网盘里看到的那条记录，指向一份物理文件。
 * <p>
 * 纯逻辑记录，本身不占磁盘。用户给文件改名，只改这里的 filename，
 * 磁盘上的文件一动不动 —— 这也是把两张表拆开的直接好处。
 */
@Data
@TableName("user_file")
public class UserFile {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 指向 real_file.id，真正的文件内容在那张表对应路径上；文件夹行没有物理文件，为 NULL */
    private Long realFileId;

    /** 原始文件名，用户看到的就是它 */
    private String filename;

    /** 父目录 id，0 表示根目录 */
    private Long parentId;

    /** 0 正常 1 回收站，配合 @TableLogic 可以做到"删除只是标记" */
    private Integer isDelete;

    /**
     * 0 文件 1 文件夹。
     * <p>
     * 用一张表存文件与文件夹，是因为它们的"归属、父目录、回收站、恢复"语义完全一样，
     * 区别只有两处：文件夹没有 real_file_id、不能下载。
     * 如果拆成两张表，列表要 union、回收站要 union、恢复/彻底删除都要写两套，复杂度反而更高。
     */
    private Integer isFolder;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
