package com.easysys.api.controller;

import com.easysys.api.dto.template.TemplateRequest;
import com.easysys.api.dto.template.TemplateView;
import com.easysys.api.service.TemplateService;
import com.easysys.common.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 触达模板：CRUD。执行期按 channel + templateId 路由渲染。
 */
@RestController
@RequestMapping("/api/templates")
public class TemplateController {

    private final TemplateService templateService;

    public TemplateController(TemplateService templateService) {
        this.templateService = templateService;
    }

    @GetMapping
    public ApiResponse<List<TemplateView>> list() {
        return ApiResponse.ok(templateService.list());
    }

    @PostMapping
    public ApiResponse<TemplateView> create(@Valid @RequestBody TemplateRequest req,
                                            @RequestAttribute String username) {
        return ApiResponse.ok(templateService.create(req, username));
    }

    @GetMapping("/{id}")
    public ApiResponse<TemplateView> get(@PathVariable Long id) {
        return ApiResponse.ok(templateService.get(id));
    }

    @PutMapping("/{id}")
    public ApiResponse<TemplateView> update(@PathVariable Long id,
                                            @Valid @RequestBody TemplateRequest req) {
        return ApiResponse.ok(templateService.update(id, req));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        templateService.delete(id);
        return ApiResponse.ok(null);
    }
}