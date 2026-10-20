package backend.vo;

import backend.entity.User;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 返回给前端的用户信息。
 * <p>
 * 关键点：这个类根本没有 password 字段，所以不可能泄露密码。
 * 比"在实体上加 @JsonIgnore"或"手动 setPassword(null)"更可靠——那两种写法一旦漏掉就会出事故，
 * 而这里是从类型层面就写不出来。
 */
@Data
public class UserInfoVO {

    private Long id;
    private String username;
    private String email;
    /** 容量上限，单位字节，前端自己换算成 GB 展示 */
    private Long storageLimit;
    private LocalDateTime createTime;

    /** 只挑选允许对外暴露的字段做转换 */
    public static UserInfoVO from(User user) {
        UserInfoVO vo = new UserInfoVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setEmail(user.getEmail());
        vo.setStorageLimit(user.getStorageLimit());
        vo.setCreateTime(user.getCreateTime());
        return vo;
    }
}
