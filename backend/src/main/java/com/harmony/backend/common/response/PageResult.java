package com.harmony.backend.common.response;

import lombok.Data;

import java.util.List;

@Data
public class PageResult<T> {
    private List<T> content;
    private long totalElements;
    private int totalPages;
    private int pageNumber;
    private int pageSize;
}
