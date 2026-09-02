package com.easysys.api.controller;

import com.easysys.api.dto.audience.AudienceRequest;
import com.easysys.api.dto.audience.AudienceResponse;
import com.easysys.api.dto.audience.MemberView;
import com.easysys.api.dto.audience.SnapshotResponse;
import com.easysys.api.service.AudienceService;
import com.easysys.common.web.ApiResponse;
import com.easysys.common.web.PageResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AudienceController {

    private final AudienceService audienceService;

    public AudienceController(AudienceService audienceService) {
        this.audienceService = audienceService;
    }

    @GetMapping("/audiences")
    public ApiResponse<PageResponse<AudienceResponse>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return ApiResponse.ok(audienceService.list(ContactController.normPage(page), ContactController.normSize(size)));
    }

    @PostMapping("/audiences")
    public ApiResponse<AudienceResponse> create(@Valid @RequestBody AudienceRequest req,
                                                @RequestAttribute String username) {
        return ApiResponse.ok(audienceService.create(req, username));
    }

    @GetMapping("/audiences/{id}")
    public ApiResponse<AudienceResponse> get(@PathVariable Long id) {
        return ApiResponse.ok(audienceService.get(id));
    }

    @PutMapping("/audiences/{id}")
    public ApiResponse<AudienceResponse> update(@PathVariable Long id, @Valid @RequestBody AudienceRequest req) {
        return ApiResponse.ok(audienceService.update(id, req));
    }

    @DeleteMapping("/audiences/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        audienceService.delete(id);
        return ApiResponse.ok(null);
    }

    /** 触发圈选，同步执行并返回冻结快照。 */
    @PostMapping("/audiences/{id}/snapshot")
    public ApiResponse<SnapshotResponse> circle(@PathVariable Long id) {
        return ApiResponse.ok(audienceService.circle(id));
    }

    @GetMapping("/audiences/{id}/snapshots")
    public ApiResponse<PageResponse<SnapshotResponse>> snapshots(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return ApiResponse.ok(audienceService.snapshots(id, ContactController.normPage(page), ContactController.normSize(size)));
    }

    /** 快照成员分页预览（列表跨人群路径，避免与 /audiences/{id} 冲突）。 */
    @GetMapping("/snapshots/{id}/members")
    public ApiResponse<PageResponse<MemberView>> members(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return ApiResponse.ok(audienceService.members(id, ContactController.normPage(page), ContactController.normSize(size)));
    }
}