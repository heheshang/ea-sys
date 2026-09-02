package com.easysys.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.easysys.api.entity.ContactAttribute;
import com.easysys.api.mapper.ContactAttributeMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 分层打标器：把 AGENT_SPLIT 计算出的 layer 写入 contact_attribute（key=layer，jsonb 字符串）。
 * 只 upsert 缺失/变化的行，不触碰其他画像属性。
 */
@Service
public class LayerTagger {

    public static final String LAYER_KEY = "layer";
    private static final int BATCH = 500;

    private final ContactAttributeMapper attributeMapper;
    private final ObjectMapper json;

    public LayerTagger(ContactAttributeMapper attributeMapper, ObjectMapper json) {
        this.attributeMapper = attributeMapper;
        this.json = json;
    }

    /** 批量 upsert：contactId → layer。值相同跳过；缺失插入；不同更新。 */
    @Transactional
    public void mark(Long tenantId, Map<Long, String> layers) {
        if (layers == null || layers.isEmpty()) {
            return;
        }
        List<Map.Entry<Long, String>> todo = new ArrayList<>(layers.entrySet());
        for (int i = 0; i < todo.size(); i += BATCH) {
            List<Long> batchIds = todo.subList(i, Math.min(i + BATCH, todo.size()))
                    .stream().map(Map.Entry::getKey).toList();
            Map<Long, ContactAttribute> existing = new HashMap<>();
            for (ContactAttribute a : attributeMapper.selectList(new LambdaQueryWrapper<ContactAttribute>()
                    .eq(ContactAttribute::getKey, LAYER_KEY)
                    .in(ContactAttribute::getContactId, batchIds))) {
                existing.put(a.getContactId(), a);
            }
            for (Map.Entry<Long, String> e : todo.subList(i, Math.min(i + BATCH, todo.size()))) {
                String value = writeJson(e.getValue());
                ContactAttribute row = existing.get(e.getKey());
                if (row == null) {
                    ContactAttribute a = new ContactAttribute();
                    a.setTenantId(tenantId);
                    a.setContactId(e.getKey());
                    a.setKey(LAYER_KEY);
                    a.setValue(value);
                    a.setUpdatedAt(Instant.now());
                    attributeMapper.insert(a);
                } else if (!value.equals(row.getValue())) {
                    row.setValue(value);
                    row.setUpdatedAt(Instant.now());
                    attributeMapper.updateById(row);
                }
            }
        }
    }

    private String writeJson(String scalar) {
        try {
            return json.writeValueAsString(scalar);
        } catch (Exception e) {
            throw new IllegalStateException("layer 值序列化失败: " + scalar, e);
        }
    }

    /** 读取单个联系人当前 layer（无 → null）。供路由预览/测试查询。 */
    public String layerOf(Long tenantId, Long contactId) {
        ContactAttribute a = attributeMapper.selectOne(new LambdaQueryWrapper<ContactAttribute>()
                .eq(ContactAttribute::getKey, LAYER_KEY)
                .eq(ContactAttribute::getContactId, contactId)
                .last("LIMIT 1"));
        if (a == null || a.getValue() == null) {
            return null;
        }
        try {
            return json.readTree(a.getValue()).asText();
        } catch (Exception e) {
            return null;
        }
    }
}