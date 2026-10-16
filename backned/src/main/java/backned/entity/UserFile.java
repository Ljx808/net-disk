package backned.entity;

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

    /** 指向 real_file.id，真正的文件内容在那张表对应路径上 */
    private Long realFileId;

    /** 原始文件名，用户看到的就是它 */
    private String filename;

    /** 父目录 id，0 表示根目录。Day4 固定传 0，为 Day5 的目录结构预留 */
    private Long parentId;

    /** 0 正常 1 回收站，配合 @TableLogic 可以做到"删除只是标记" */
    private Integer isDelete;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
