package com.llm.gateway.admin.web;

/** Validated pagination bounds shared by management list endpoints. */
public record PageBounds(long page, long size) {

    public static PageBounds of(long page, long size) {
        if (page < 1) {
            throw AdminApiException.badRequest("page 必须大于等于 1");
        }
        if (size < 1 || size > 100) {
            throw AdminApiException.badRequest("size 必须在 1 到 100 之间");
        }
        return new PageBounds(page, size);
    }
}
