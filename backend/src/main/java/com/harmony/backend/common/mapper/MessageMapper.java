package com.harmony.backend.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.harmony.backend.common.entity.Message;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface MessageMapper extends BaseMapper<Message> {

    @Select("""
            <script>
            SELECT id, message_id, chat_id, parent_message_id, role, content, tokens, model, status, is_deleted, created_at, updated_at
            FROM message
            WHERE chat_id = #{chatId}
              AND (is_deleted IS NULL OR is_deleted = 0)
            ORDER BY created_at DESC, id DESC
            LIMIT #{limit}
            </script>
            """)
    List<Message> selectRecentByChatId(@Param("chatId") String chatId, @Param("limit") int limit);

    @Select("""
            SELECT id, message_id, chat_id, parent_message_id, role, content, tokens, model, status, is_deleted, created_at, updated_at
            FROM message
            WHERE chat_id = #{chatId}
              AND message_id = #{messageId}
              AND (is_deleted IS NULL OR is_deleted = 0)
            LIMIT 1
            """)
    Message selectByChatIdAndMessageId(@Param("chatId") String chatId, @Param("messageId") String messageId);
}
