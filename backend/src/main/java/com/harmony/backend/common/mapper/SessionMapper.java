package com.harmony.backend.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.harmony.backend.common.entity.Session;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface SessionMapper extends BaseMapper<Session> {

    @Update("""
            <script>
            UPDATE chat_session
            SET message_count = COALESCE(message_count, 0) + #{messageDelta},
                last_active_time = #{lastActiveTime},
                current_message_id =
                    <choose>
                        <when test="currentMessageId != null and currentMessageId != ''">#{currentMessageId}</when>
                        <otherwise>current_message_id</otherwise>
                    </choose>
            WHERE chat_id = #{chatId}
              AND is_deleted = 0
            </script>
            """)
    int incrementMessageCount(@Param("chatId") String chatId,
                              @Param("messageDelta") int messageDelta,
                              @Param("currentMessageId") String currentMessageId,
                              @Param("lastActiveTime") LocalDateTime lastActiveTime);
}
