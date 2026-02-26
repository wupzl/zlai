package com.harmony.backend.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.harmony.backend.common.entity.SystemLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SystemLogMapper extends BaseMapper<SystemLog> {
}
