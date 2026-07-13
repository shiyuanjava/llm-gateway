package com.llm.gateway.admin.web;

/**
 * 管理端统一响应包装：{@code {code, msg, data}}。
 *
 * @param code 业务码，0 表示成功
 * @param msg  提示信息
 * @param data 数据
 * @param <T>  数据类型
 */
public record R<T>(int code, String msg, T data) {

    /**
     * 成功（带数据）。
     *
     * @param data 数据
     * @param <T>  数据类型
     * @return 响应
     */
    public static <T> R<T> ok(T data) {
        return new R<>(0, "ok", data);
    }

    /**
     * 成功（无数据）。
     *
     * @param <T> 数据类型
     * @return 响应
     */
    public static <T> R<T> ok() {
        return new R<>(0, "ok", null);
    }
}
