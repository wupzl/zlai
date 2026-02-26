package com.harmony.backend.common.util;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.harmony.backend.common.response.PageResult;

public final class PageResultUtils {

    private PageResultUtils() {
    }

    public static <T> PageResult<T> from(Page<T> page) {
        PageResult<T> pageResult = new PageResult<>();
        if (page == null) {
            return pageResult;
        }
        pageResult.setContent(page.getRecords());
        pageResult.setTotalElements(page.getTotal());
        pageResult.setTotalPages((int) page.getPages());
        pageResult.setPageNumber((int) page.getCurrent());
        pageResult.setPageSize((int) page.getSize());
        return pageResult;
    }
}
