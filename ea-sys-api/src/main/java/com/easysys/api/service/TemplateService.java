package com.easysys.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.easysys.api.dto.template.TemplateRequest;
import com.easysys.api.dto.template.TemplateView;
import com.easysys.common.tenant.TenantContext;
import com.easysys.common.web.BizException;
import com.easysys.common.web.ErrorCode;
import com.easysys.engine.entity.Template;
import com.easysys.engine.mapper.TemplateMapper;
import com.easysys.engine.service.TemplateRenderer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 触达模板：CRUD + 渲染校验（减少执行期失败面，模板坏在执行期只影响单个节点报告）。
 */
@Service
public class TemplateService {

    private final TemplateMapper templateMapper;
    private final TemplateRenderer templateRenderer;

    public TemplateService(TemplateMapper templateMapper, TemplateRenderer templateRenderer) {
        this.templateMapper = templateMapper;
        this.templateRenderer = templateRenderer;
    }

    @Transactional
    public TemplateView create(TemplateRequest req, String operator) {
        Long tenantId = TenantContext.require();
        renderCheck(req.content()); // 语法校验；坏模板 400
        Template t = new Template();
        t.setTenantId(tenantId);
        t.setChannel(req.channel().trim());
        t.setName(req.name().trim());
        t.setContent(req.content());
        t.setStatus("enabled");
        t.setCreatedBy(operator);
        t.setCreatedAt(Instant.now());
        t.setUpdatedAt(Instant.now());
        templateMapper.insert(t);
        return toView(t);
    }

    @Transactional
    public TemplateView update(Long id, TemplateRequest req) {
        Template t = require(id);
        renderCheck(req.content()); // 语法校验
        t.setChannel(req.channel().trim());
        t.setName(req.name().trim());
        t.setContent(req.content());
        t.setUpdatedAt(Instant.now());
        templateMapper.updateById(t);
        return toView(t);
    }

    public List<TemplateView> list() {
        List<Template> rows = templateMapper.selectList(
                new LambdaQueryWrapper<Template>()
                        .eq(Template::getStatus, "enabled")
                        .orderByAsc(Template::getId));
        return rows.stream().map(TemplateService::toView).toList();
    }

    public TemplateView get(Long id) {
        return toView(require(id));
    }

    @Transactional
    public void delete(Long id) {
        require(id);
        templateMapper.deleteById(id);
    }

    private void renderCheck(String content) {
        try {
            templateRenderer.render(content, java.util.Map.of());
        } catch (Exception e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "模板语法错误: " + e.getMessage());
        }
    }

    private Template require(Long id) {
        Template t = templateMapper.selectById(id);
        if (t == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "模板不存在: " + id);
        }
        return t;
    }

    private static TemplateView toView(Template t) {
        return new TemplateView(t.getId(), t.getChannel(), t.getName(), t.getContent(),
                t.getStatus(), t.getCreatedAt());
    }
}