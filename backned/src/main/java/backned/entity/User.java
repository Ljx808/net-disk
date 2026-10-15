package backned.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户实体，对应数据库 user 表。
 * <p>
 * 注意：这个类只在 service/mapper 内部流转，不直接返回给前端（因为带 password），
 * 对外统一用 UserInfoVO。
 */
@Data
@TableName("user")
public class User {

    /** 数据库自增主键，IdType.AUTO 表示交给 MySQL 生成，插入后 MyBatis-Plus 会回填 id */
    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    /** 存的是 BCrypt 密文，不是明文，长度 60 字符 */
    private String password;

    private String email;

    /** 网盘容量上限（字节），数据库默认 1GB，插入时留 null 即可 */
    private Long storageLimit;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
