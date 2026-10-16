package backned.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 物理文件：磁盘上真实存在的那份文件，全站按 md5 唯一。
 * <p>
 * 它和"用户文件"是一对多关系：一份物理文件可以被很多个用户引用。
 * 所以这张表里没有 user_id —— 一旦放了用户，同一个文件就又存了多份。
 */
@Data
@TableName("real_file")
public class RealFile {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 文件内容的 md5，唯一索引。用它判断"这个文件是不是已经在磁盘上了" */
    private String md5;

    /** 相对存储根目录的路径。存相对路径而不是绝对路径，换存储目录时老数据不用改 */
    private String filePath;

    private Long fileSize;

    /** 文件类型，如 image/png，下载时用来设置响应头 */
    private String mimeType;

    /** 被多少条 user_file 引用。没人引用了才允许删物理文件，避免删掉别人还在用的文件 */
    private Integer refCount;

    private LocalDateTime createTime;
}
