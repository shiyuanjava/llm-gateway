package com.llm.gateway.admin.web;

import java.util.List;

/**
 * 分页结果。
 *
 * @param records 当前页记录
 * @param total   总条数
 * @param current 当前页码
 * @param size    每页大小
 * @param <T>     记录类型
 */
public record PageResult<T>(List<T> records, long total, long current, long size) {}
