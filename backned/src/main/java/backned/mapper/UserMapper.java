package backned.mapper;

import backned.entity.User;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * user 表的数据访问层。
 * <p>
 * 继承 BaseMapper 后，单表增删改查（insert/selectById/selectOne/selectCount...）全部自带，不用写 XML。
 * 加 @Mapper 注解后 MyBatis-Plus 会自动扫描到它，所以主启动类上不需要再写 @MapperScan。
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
