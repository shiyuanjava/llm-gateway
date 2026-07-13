package com.llm.gateway.audit;

import java.time.LocalDateTime;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.llm.gateway.admin.web.PageResult;
import com.llm.gateway.admin.web.R;
import com.llm.gateway.persistence.entity.AdminAuditLogEntity;
import com.llm.gateway.persistence.mapper.AdminAuditLogMapper;

/**
 * 管理面审计日志查询接口（{@code /admin/audit-logs}）。
 */
@RestController
@RequestMapping("/admin/audit-logs")
public class AuditAdminController {

    private final AdminAuditLogMapper mapper;

    public AuditAdminController(AdminAuditLogMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 分页查询审计日志（时间倒序）。
     *
     * @param username 按用户名精确筛选，可空
     * @param action   按动作精确筛选，可空
     * @param from     创建时间下界（含，ISO-8601 如 2026-07-11T00:00:00），可空
     * @param to       创建时间上界（含），可空
     * @param page     页码（1 起）
     * @param size     每页大小
     * @return 分页结果
     */
    @GetMapping
    public R<PageResult<AdminAuditLogEntity>> list(
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        LambdaQueryWrapper<AdminAuditLogEntity> query = Wrappers.<AdminAuditLogEntity>lambdaQuery()
                .eq(username != null && !username.isBlank(), AdminAuditLogEntity::getUsername, username)
                .eq(action != null && !action.isBlank(), AdminAuditLogEntity::getAction, action)
                .ge(from != null, AdminAuditLogEntity::getCreatedAt, from)
                .le(to != null, AdminAuditLogEntity::getCreatedAt, to)
                .orderByDesc(AdminAuditLogEntity::getId);
        Page<AdminAuditLogEntity> p = mapper.selectPage(new Page<>(page, size), query);
        return R.ok(new PageResult<>(p.getRecords(), p.getTotal(), p.getCurrent(), p.getSize()));
    }
}
