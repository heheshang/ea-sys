package com.easysys.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.easysys.api.dto.channel.ChannelConfigView;
import com.easysys.api.entity.ChannelConfig;
import com.easysys.api.mapper.ChannelConfigMapper;
import com.easysys.channel.ChannelConfigProvider;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.encrypt.AesBytesEncryptor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 通道凭据配置：AES 加密落库（spring-security-crypto，密钥经 easysys.channel.encrypt-password 注入，
 * 生产以环境变量 CHANNEL_ENCRYPT_PASSWORD 覆盖），读取时解密；对外视图脱敏。
 * 实现 {@link ChannelConfigProvider} 供通道适配器按租户注入凭据。
 */
@Service
public class ChannelConfigService implements ChannelConfigProvider {

    private static final String MASK = "******";

    private final ChannelConfigMapper mapper;
    private final ObjectMapper json;
    private final AesBytesEncryptor encryptor;

    public ChannelConfigService(ChannelConfigMapper mapper, ObjectMapper json,
                                @Value("${easysys.channel.encrypt-password:ea-sys-dev-channel-key}") String password) {
        this.mapper = mapper;
        this.json = json;
        this.encryptor = new AesBytesEncryptor(password, "65612d7379732d6368616e6e656c");
    }

    /** 保存（按 tenant + channel 幂等 upsert，加密落库）。租户取自上下文，tenantId 参数须与上下文一致。 */
    @Transactional
    public ChannelConfigView save(Long tenantId, String channel, Map<String, String> config, Boolean enabled) {
        ChannelConfig row = select(tenantId, channel);
        String encrypted = encrypt(config);
        if (row == null) {
            row = new ChannelConfig();
            row.setTenantId(tenantId);
            row.setChannel(channel);
            row.setConfigEncrypted(encrypted);
            row.setEnabled(enabled == null || enabled);
            row.setCreatedAt(Instant.now());
            row.setUpdatedAt(row.getCreatedAt());
            mapper.insert(row);
        } else {
            row.setConfigEncrypted(encrypted);
            row.setEnabled(enabled == null || enabled);
            row.setUpdatedAt(Instant.now());
            mapper.updateById(row);
        }
        return toView(row);
    }

    /** 当前租户全部通道配置（脱敏后返回）。 */
    public List<ChannelConfigView> list(Long tenantId, String channel) {
        List<ChannelConfig> rows = mapper.selectList(new LambdaQueryWrapper<ChannelConfig>()
                .eq(channel != null && !channel.isBlank(), ChannelConfig::getChannel, channel)
                .orderByAsc(ChannelConfig::getChannel));
        return rows.stream().map(this::toView).toList();
    }

    /** 删除通道配置（逻辑删除）；不存在则静默。 */
    @Transactional
    public void delete(Long tenantId, String channel) {
        ChannelConfig row = select(tenantId, channel);
        if (row != null) {
            mapper.deleteById(row.getId());
        }
    }

    /** 适配器凭据加载：enabled=FALSE 或未配置 → empty（适配器降级 console）。 */
    @Override
    public Optional<Map<String, String>> load(Long tenantId, String channel) {
        ChannelConfig row = select(tenantId, channel);
        if (row == null || !Boolean.TRUE.equals(row.getEnabled())) {
            return Optional.empty();
        }
        return Optional.ofNullable(decrypt(row.getConfigEncrypted()));
    }

    private ChannelConfig select(Long tenantId, String channel) {
        return mapper.selectOne(new LambdaQueryWrapper<ChannelConfig>()
                .eq(ChannelConfig::getTenantId, tenantId)
                .eq(ChannelConfig::getChannel, channel));
    }

    private ChannelConfigView toView(ChannelConfig row) {
        Map<String, String> cfg = decrypt(row.getConfigEncrypted());
        Map<String, String> masked = new LinkedHashMap<>(cfg);
        masked.replaceAll((k, v) -> isSecretKey(k) ? MASK : v);
        return new ChannelConfigView(row.getId(), row.getChannel(), row.getEnabled(), masked, row.getUpdatedAt());
    }

    private static boolean isSecretKey(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        return k.contains("password") || k.contains("secret") || k.contains("apikey");
    }

    private String encrypt(Map<String, String> config) {
        try {
            byte[] raw = encryptor.encrypt(json.writeValueAsBytes(config));
            return Base64.getEncoder().encodeToString(raw);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("通道凭据序列化失败", e);
        }
    }

    private Map<String, String> decrypt(String stored) {
        try {
            byte[] raw = Base64.getDecoder().decode(stored);
            return json.readValue(encryptor.decrypt(raw), new TypeReference<Map<String, String>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("通道凭据解密失败", e);
        }
    }
}