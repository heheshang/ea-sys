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
 * 画像打标器：把智能体计算结果写入 contact_attribute（jsonb 字符串）。
 * LAYER → key=layer（AGENT_SPLIT 分流依据）；CHURN → key=churn_risk（流失预警回写）。
 * 只 upsert 缺失/变化的行，不触碰其他画像属性。
 */
@Service
public class LayerTagger {

    public static final String LAYER_KEY = "layer";
    public static final String CHURN_RISK_KEY = "churn_risk";
    private static final int BATCH = 500;

    private final ContactAttributeMapper attributeMapper;
    private final ObjectMapper json;

    public LayerTagger(ContactAttributeMapper attributeMapper, ObjectMapper json) {
        this.attributeMapper = attributeMapper;
        this.json = json;
    }

    /** 批量 upsert layer：contactId → layer（托管 {@link #LAYER_KEY}）。 */
    @Transactional
    public void mark(Long tenantId, Map<Long, String> layers) {
        upsert(tenantId, LAYER_KEY, layers);
    }

    /** 批量回写流失风险等级：contactId → tier（ChurnService 使用）。 */
    @Transactional
    public void markChurnRisk(Long tenantId, Map<Long, String> tiers) {
        upsert(tenantId, CHURN_RISK_KEY, tiers);
    }

    /** 批量 upsert：contactId → scalar（jsonb 字符串）。值相同跳过；缺失插入；不同更新。 */
    public void upsert(Long tenantId, String key, Map<Long, String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        List<Map.Entry<Long, String>> todo = new ArrayList<>(values.entrySet());
        for (int i = 0; i < todo.size(); i += BATCH) {
            List<Long> batchIds = todo.subList(i, Math.min(i + BATCH, todo.size()))
                    .stream().map(Map.Entry::getKey).toList();
            Map<Long, ContactAttribute> existing = new HashMap<>();
            for (ContactAttribute a : attributeMapper.selectList(new LambdaQueryWrapper<ContactAttribute>()
                    .eq(ContactAttribute::getKey, key)
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
                    a.setKey(key);
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