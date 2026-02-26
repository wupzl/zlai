package com.harmony.backend.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.harmony.backend.common.entity.AppConfig;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AppConfigMapper extends BaseMapper<AppConfig> {
}
