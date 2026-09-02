package com.easysys.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.easysys.api.dto.audience.BatchContactCreateRequest;
import com.easysys.api.dto.audience.BatchContactCreateResult;
import com.easysys.api.dto.audience.ContactRequest;
import com.easysys.api.dto.audience.ContactResponse;
import com.easysys.api.entity.Contact;
import com.easysys.api.entity.ContactAttribute;
import com.easysys.api.entity.ContactTag;
import com.easysys.api.mapper.ContactAttributeMapper;
import com.easysys.api.mapper.ContactMapper;
import com.easysys.api.mapper.ContactTagMapper;
import com.easysys.common.tenant.TenantContext;
import com.easysys.common.web.BizException;
import com.easysys.common.web.ErrorCode;
import com.easysys.common.web.PageResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 接触库：contact + 属性/标签（全量替换语义）。
 */
@Service
public class ContactService {

    private static final int PROFILE_BATCH = 200;

    private final ContactMapper contactMapper;
    private final ContactAttributeMapper attributeMapper;
    private final ContactTagMapper tagMapper;
    private final ObjectMapper json;

    public ContactService(ContactMapper contactMapper, ContactAttributeMapper attributeMapper,
                          ContactTagMapper tagMapper, ObjectMapper json) {
        this.contactMapper = contactMapper;
        this.attributeMapper = attributeMapper;
        this.tagMapper = tagMapper;
        this.json = json;
    }

    @Transactional
    public ContactResponse create(ContactRequest req) {
        Contact c = new Contact();
        applyBase(c, req, true);
        try {
            contactMapper.insert(c);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "external_id 或 phone 与现有接触对象冲突");
        }
        replaceProfile(c.getId(), req);
        return loadResponse(c.getId());
    }

    /** 单次最多尝试生成数（极端碰撞保护）。 */
    private static final int BATCH_MAX_ATTEMPTS = 50_000;

    private static final String[] CHURN_RISKS = { "LOW", "MEDIUM", "HIGH" };

    /**
     * 批量随机创建联系人：externalId/手机号随机唯一，画像属性随机（churn_risk、level），
     * 供圈选与触达验证。真实下发的联系人同样落在 contact 表，圈选快照即可纳入人群。
     */
    @Transactional
    public BatchContactCreateResult batchCreate(BatchContactCreateRequest req) {
        Long tenantId = TenantContext.require();
        SecureRandom rnd = new SecureRandom();
        Instant now = Instant.now();
        Set<String> phones = new HashSet<>();
        Set<String> extIds = new HashSet<>();
        int created = 0;
        int attempts = 0;
        while (created < req.count() && attempts < BATCH_MAX_ATTEMPTS) {
            attempts++;
            String phone = randomPhone(rnd);
            String ext = "rand-" + randomToken(rnd, 8);
            if (!phones.add(phone) || !extIds.add(ext)) {
                continue;
            }
            Contact c = new Contact();
            c.setTenantId(tenantId);
            c.setExternalId(ext);
            c.setPhone(phone);
            c.setStatus("active");
            c.setSuppression("{}");
            c.setCreatedAt(now);
            c.setUpdatedAt(now);
            try {
                contactMapper.insert(c);
            } catch (DuplicateKeyException e) {
                // 撞库存号码/externalId（随机碰撞，概率极低）→ 放弃该组随机值重试
                phones.remove(phone);
                extIds.remove(ext);
                continue;
            }
            insertRandomProfile(c.getId(), tenantId, now, rnd);
            created++;
        }
        return new BatchContactCreateResult(created, req.count() - created);
    }

    /** 随机画像属性（2 条）：churn_risk 命中流失人群规则，level 命中 VIP 人群规则。 */
    private void insertRandomProfile(Long contactId, Long tenantId, Instant now, SecureRandom rnd) {
        List<ContactAttribute> attrs = new ArrayList<>(2);
        attrs.add(attr(contactId, tenantId, now, "churn_risk", CHURN_RISKS[rnd.nextInt(CHURN_RISKS.length)]));
        attrs.add(attr(contactId, tenantId, now, "level", 1 + rnd.nextInt(5)));
        attrs.forEach(attributeMapper::insert);
    }

    private ContactAttribute attr(Long contactId, Long tenantId, Instant now, String key, Object value) {
        ContactAttribute a = new ContactAttribute();
        a.setTenantId(tenantId);
        a.setContactId(contactId);
        a.setKey(key);
        a.setValue(writeJson(value));
        a.setUpdatedAt(now);
        return a;
    }

    /** 11 位大陆手机号（1[3-9] + 9 位随机）。 */
    private static String randomPhone(SecureRandom rnd) {
        StringBuilder sb = new StringBuilder("1");
        sb.append(3 + rnd.nextInt(7));
        for (int i = 0; i < 9; i++) {
            sb.append(rnd.nextInt(10));
        }
        return sb.toString();
    }

    private static String randomToken(SecureRandom rnd, int len) {
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(chars.charAt(rnd.nextInt(chars.length())));
        }
        return sb.toString();
    }

    @Transactional
    public ContactResponse update(Long id, ContactRequest req) {
        Contact c = requireContact(id);
        applyBase(c, req, false);
        c.setUpdatedAt(Instant.now());
        contactMapper.updateById(c);
        replaceProfile(id, req);
        return loadResponse(id);
    }

    public PageResponse<ContactResponse> list(String keyword, long page, long size) {
        LambdaQueryWrapper<Contact> w = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            String like = "%" + keyword.trim() + "%";
            w.and(q -> q.like(Contact::getPhone, like)
                    .or().like(Contact::getEmail, like)
                    .or().like(Contact::getExternalId, like));
        }
        w.orderByDesc(Contact::getCreatedAt);
        IPage<Contact> p = contactMapper.selectPage(new Page<>(page, size), w);
        List<Contact> contacts = p.getRecords();
        Map<Long, List<ContactAttribute>> attrsByContact = loadAttrs(contacts);
        Map<Long, List<ContactTag>> tagsByContact = loadTags(contacts);
        List<ContactResponse> records = contacts.stream()
                .map(c -> toResponse(c, attrsByContact.getOrDefault(c.getId(), List.of()),
                        tagsByContact.getOrDefault(c.getId(), List.of())))
                .toList();
        return PageResponse.of(records, p.getTotal(), page, size);
    }

    public ContactResponse get(Long id) {
        return loadResponse(id);
    }

    @Transactional
    public void delete(Long id) {
        requireContact(id);
        contactMapper.deleteById(id);
    }

    private void applyBase(Contact c, ContactRequest req, boolean create) {
        c.setTenantId(TenantContext.require());
        c.setExternalId(blankToNull(req.externalId()));
        c.setPhone(blankToNull(req.phone()));
        c.setEmail(blankToNull(req.email()));
        c.setPushToken(blankToNull(req.pushToken()));
        if (create) {
            c.setStatus(StringUtils.hasText(req.status()) ? req.status() : "active");
            c.setSuppression("{}");
        } else if (StringUtils.hasText(req.status())) {
            c.setStatus(req.status());
        }
    }

    private void replaceProfile(Long contactId, ContactRequest req) {
        // 属性全量替换
        attributeMapper.delete(new LambdaQueryWrapper<ContactAttribute>().eq(ContactAttribute::getContactId, contactId));
        tagMapper.delete(new LambdaQueryWrapper<ContactTag>().eq(ContactTag::getContactId, contactId));
        if (req.attributes() != null && !req.attributes().isEmpty()) {
            List<ContactAttribute> attrs = new ArrayList<>();
            for (Map.Entry<String, Object> e : req.attributes().entrySet()) {
                if (!StringUtils.hasText(e.getKey())) {
                    continue;
                }
                ContactAttribute a = new ContactAttribute();
                a.setTenantId(TenantContext.require());
                a.setContactId(contactId);
                a.setKey(e.getKey().trim());
                a.setValue(writeJson(e.getValue()));
                a.setUpdatedAt(Instant.now());
                attrs.add(a);
            }
            for (int i = 0; i < attrs.size(); i += PROFILE_BATCH) {
                attrs.subList(i, Math.min(i + PROFILE_BATCH, attrs.size()))
                        .forEach(attributeMapper::insert);
            }
        }
        if (req.tags() != null && !req.tags().isEmpty()) {
            List<ContactTag> tags = new ArrayList<>();
            for (String t : req.tags()) {
                if (!StringUtils.hasText(t)) {
                    continue;
                }
                ContactTag ct = new ContactTag();
                ct.setTenantId(TenantContext.require());
                ct.setContactId(contactId);
                ct.setTag(t.trim());
                tags.add(ct);
            }
            tags.forEach(tagMapper::insert);
        }
    }

    private Map<Long, List<ContactAttribute>> loadAttrs(List<Contact> contacts) {
        if (contacts.isEmpty()) {
            return Map.of();
        }
        Set<Long> ids = contacts.stream().map(Contact::getId).collect(Collectors.toSet());
        List<ContactAttribute> all = attributeMapper.selectList(new LambdaQueryWrapper<ContactAttribute>()
                .in(ContactAttribute::getContactId, ids).orderByAsc(ContactAttribute::getKey));
        return all.stream().collect(Collectors.groupingBy(ContactAttribute::getContactId));
    }

    private Map<Long, List<ContactTag>> loadTags(List<Contact> contacts) {
        if (contacts.isEmpty()) {
            return Map.of();
        }
        Set<Long> ids = contacts.stream().map(Contact::getId).collect(Collectors.toSet());
        List<ContactTag> all = tagMapper.selectList(new LambdaQueryWrapper<ContactTag>()
                .in(ContactTag::getContactId, ids).orderByAsc(ContactTag::getTag));
        return all.stream().collect(Collectors.groupingBy(ContactTag::getContactId));
    }

    private ContactResponse toResponse(Contact c, List<ContactAttribute> attrs, List<ContactTag> tags) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        List<String> tagList = new ArrayList<>();
        attrs.forEach(a -> attributes.put(a.getKey(), readJson(a.getValue())));
        tags.forEach(t -> tagList.add(t.getTag()));
        return new ContactResponse(c.getId(), c.getExternalId(), c.getPhone(), c.getEmail(),
                c.getPushToken(), c.getWechatOpenid(), c.getStatus(), c.getCreatedAt(), c.getUpdatedAt(),
                attributes, tagList);
    }

    private ContactResponse loadResponse(Long id) {
        Contact c = requireContact(id);
        List<Contact> one = List.of(c);
        return toResponse(c, loadAttrs(one).getOrDefault(id, List.of()), loadTags(one).getOrDefault(id, List.of()));
    }

    private Contact requireContact(Long id) {
        Contact c = contactMapper.selectById(id);
        if (c == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "接触对象不存在: " + id);
        }
        return c;
    }

    private String writeJson(Object v) {
        try {
            if (v instanceof String s) {
                // 字符串属性存 jsonb 字符串字面量（"L1"），保持标量语义
                return json.writeValueAsString(s);
            }
            return json.writeValueAsString(v == null ? "" : v);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "属性值不是合法 JSON 标量");
        }
    }

    private Object readJson(String raw) {
        try {
            return json.readValue(raw, Object.class);
        } catch (JsonProcessingException e) {
            return raw;
        }
    }

    private static String blankToNull(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
    }
}