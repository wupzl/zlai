package com.harmony.backend.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.harmony.backend.common.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface UserMapper extends BaseMapper<User> {


    @Select("SELECT id,username, password, role, status, nickname," +
            "last_password_change, last_logout_time, token_balance, ocr_balance " +
            "FROM account WHERE username = #{username} AND is_deleted = 0")
    User selectForLogin(@Param("username") String username);

    @Update("UPDATE account SET last_login_time = #{loginTime} WHERE id = #{id}")
    int updateLastLoginTime(@Param("id") Long id, @Param("loginTime") LocalDateTime loginTime);

    @Update("UPDATE account SET last_logout_time = #{logoutTime} WHERE id = #{id}")
    int updateLastLogoutTime(@Param("id") Long id, @Param("logoutTime") LocalDateTime logoutTime);

    @Update("UPDATE account SET status = 'LOCKED', locked_until = #{lockedUntil} WHERE id = #{id}")
    int lockUser(@Param("id") Long id, @Param("lockedUntil") LocalDateTime lockedUntil);

    @Update("UPDATE account SET token_balance = token_balance + #{delta} WHERE id = #{id}")
    int updateTokenBalance(@Param("id") Long id, @Param("delta") int delta);

    @Update("UPDATE account SET ocr_balance = COALESCE(ocr_balance, #{defaultQuota}) + #{delta} WHERE id = #{id}")
    int updateOcrBalance(@Param("id") Long id, @Param("delta") int delta, @Param("defaultQuota") int defaultQuota);


}
