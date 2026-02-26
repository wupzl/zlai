package com.harmony.backend.common.service;

import java.util.List;
import java.util.Map;

public interface AppConfigService {

    String getValue(String key);

    Map<String, String> getValues(List<String> keys);

    void setValue(String key, String value, Long updatedBy);
}
