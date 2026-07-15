package com.llm.gateway.admin;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.llm.gateway.admin.web.PageResult;
import com.llm.gateway.admin.web.R;
import com.llm.gateway.persistence.entity.RequestLogEntity;
import com.llm.gateway.persistence.mapper.RequestLogMapper;

/**
 * 请求日志查询接口（{@code /admin/logs}）：分页 + 按租户/状态/模型筛选，以及按租户的用量/成本统计。
 */
@RestController
@RequestMapping("/admin/logs")
public class LogAdminController {

    private final RequestLogMapper mapper;

    public LogAdminController(RequestLogMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 分页查询请求日志。
     *
     * @param tenant 租户筛选（可空）
     * @param status 状态筛选（可空）
     * @param model  请求模型模糊筛选（可空）
     * @param from   创建时间下界（含，ISO-8601），可空
     * @param to     创建时间上界（含），可空
     * @param page   页码（从 1 起）
     * @param size   每页大小
     * @return 分页结果
     */
    @GetMapping
    public R<PageResult<RequestLogEntity>> list(
            @RequestParam(required = false) String tenant,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        QueryWrapper<RequestLogEntity> query = new QueryWrapper<>();
        if (StringUtils.hasText(tenant)) {
            query.eq("tenant", tenant);
        }
        if (StringUtils.hasText(status)) {
            query.eq("status", status);
        }
        if (StringUtils.hasText(model)) {
            query.like("requested_model", model);
        }
        if (from != null) {
            query.ge("created_at", from);
        }
        if (to != null) {
            query.le("created_at", to);
        }
        query.orderByDesc("id");

        Page<RequestLogEntity> p = mapper.selectPage(new Page<>(page, size), query);
        return R.ok(new PageResult<>(p.getRecords(), p.getTotal(), p.getCurrent(), p.getSize()));
    }

    /**
     * 按租户聚合用量与成本统计。
     *
     * @return 每租户一行：请求数、总 Token（全量，配额口径）、上游成本（不含缓存命中行）、缓存命中次数
     */
    @GetMapping("/stats")
    public R<List<StatRow>> stats() {
        QueryWrapper<RequestLogEntity> query = new QueryWrapper<>();
        query.select(
                "tenant",
                "COUNT(*) AS requests",
                "IFNULL(SUM(total_tokens), 0) AS tokens",
                // 成本=上游真实成本口径：缓存命中行没有上游调用，不计入
                "IFNULL(SUM(CASE WHEN cache_hit = 1 THEN 0 ELSE cost_usd END), 0) AS cost",
                "SUM(CASE WHEN cache_hit = 1 THEN 1 ELSE 0 END) AS cache_hits");
        query.groupBy("tenant");

        List<StatRow> rows =
                mapper.selectMaps(query).stream().map(this::toStatRow).toList();
        return R.ok(rows);
    }

    /**
     * 把聚合 Map 转成统计行。
     *
     * @param row 聚合结果
     * @return 统计行
     */
    private StatRow toStatRow(Map<String, Object> row) {
        String tenant = String.valueOf(row.get("tenant"));
        long requests = toNumber(row.get("requests")).longValue();
        long tokens = toNumber(row.get("tokens")).longValue();
        double cost = toNumber(row.get("cost")).doubleValue();
        long cacheHits = toNumber(row.get("cache_hits")).longValue();
        return new StatRow(tenant, requests, tokens, cost, cacheHits);
    }

    /**
     * 容错地把对象转 Number。
     *
     * @param value 值
     * @return Number（null 视为 0）
     */
    private Number toNumber(Object value) {
        return value instanceof Number n ? n : 0;
    }

    /**
     * 租户统计行。
     *
     * @param tenant    租户
     * @param requests  请求数
     * @param tokens    总 Token（含缓存命中行，配额消耗口径）
     * @param cost      上游成本（美元，不含缓存命中行）
     * @param cacheHits 缓存命中次数
     */
    public record StatRow(String tenant, long requests, long tokens, double cost, long cacheHits) {}
}
