package com.llm.gateway.ipcontrol;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.llm.gateway.admin.web.PageBounds;
import com.llm.gateway.admin.web.PageResult;
import com.llm.gateway.admin.web.R;
import com.llm.gateway.persistence.entity.IpBlockEntity;
import com.llm.gateway.persistence.entity.IpBlockRuleEntity;

/** IP 访问控制管理 API：规则配置、封禁列表、手动封禁与解封。 */
@RestController
@RequestMapping("/admin/ip-control")
public class IpControlAdminController {

    public record RuleRequest(
            Boolean enabled, Integer windowSeconds, Integer maxRequests, Integer blockSeconds, String whitelist) {}

    public record ManualBlockRequest(String ipAddress, Long durationSeconds, String reason) {}

    private final IpBlockService blockService;

    public IpControlAdminController(IpBlockService blockService) {
        this.blockService = blockService;
    }

    @GetMapping("/rule")
    public R<IpBlockRuleEntity> getRule() {
        return R.ok(blockService.getRule());
    }

    @PutMapping("/rule")
    public R<IpBlockRuleEntity> updateRule(@RequestBody RuleRequest request) {
        return R.ok(blockService.updateRule(
                request.enabled(),
                request.windowSeconds(),
                request.maxRequests(),
                request.blockSeconds(),
                request.whitelist()));
    }

    @GetMapping("/blocks")
    public R<PageResult<IpBlockEntity>> listBlocks(
            @RequestParam(required = false) String ipAddress,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        PageBounds bounds = PageBounds.of(page, size);
        return R.ok(blockService.listBlocks(ipAddress, source, active, bounds.page(), bounds.size()));
    }

    @PostMapping("/blocks")
    public R<IpBlockEntity> manualBlock(@RequestBody ManualBlockRequest request) {
        return R.ok(blockService.manualBlock(request.ipAddress(), request.durationSeconds(), request.reason()));
    }

    @DeleteMapping("/blocks/{id}")
    public R<Void> unblock(@PathVariable long id) {
        blockService.unblock(id);
        return R.ok();
    }

    @ExceptionHandler(IpControlValidationException.class)
    public ResponseEntity<R<Void>> handleValidation(IpControlValidationException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new R<>(400, ex.getMessage(), null));
    }
}
