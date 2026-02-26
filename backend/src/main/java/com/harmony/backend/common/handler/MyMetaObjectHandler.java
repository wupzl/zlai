package com.harmony.backend.common.handler;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class MyMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        String className = metaObject.getOriginalObject().getClass().getSimpleName();

        this.strictInsertFill(metaObject, "createdAt", LocalDateTime.class, now);
        this.strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, now);

        switch (className) {
            case "Session" -> this.strictInsertFill(metaObject, "lastActiveTime", LocalDateTime.class, now);
            case "User" -> this.strictInsertFill(metaObject, "loginTime", LocalDateTime.class, now);
            case "Order" -> this.strictInsertFill(metaObject, "payTime", LocalDateTime.class, now);
            default -> {
            }
        }
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
    }
}