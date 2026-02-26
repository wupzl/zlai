package com.harmony.backend.modules.admin.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.harmony.backend.common.entity.Message;
import com.harmony.backend.common.entity.Session;
import com.harmony.backend.common.response.PageResult;

public interface AdminChatService extends IService<Session> {
    PageResult<Session> listSessions(Long userId, String keyword, int page, int size);

    PageResult<Message> listMessages(String chatId, int page, int size);

    Session getSessionDetail(String chatId);

    Message getMessageDetail(String messageId);
}
