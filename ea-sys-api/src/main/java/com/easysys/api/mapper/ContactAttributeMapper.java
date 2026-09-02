package com.easysys.api.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.easysys.api.entity.ContactAttribute;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ContactAttributeMapper extends BaseMapper<ContactAttribute> {

    /** 指定 key 在给定人群中的标记数量。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            <script>
            SELECT COUNT(*) FROM contact_attribute
            WHERE key = #{key} AND tenant_id = #{tenantId} AND contact_id IN
            <foreach collection="contactIds" item="cid" open="(" separator="," close=")">#{cid}</foreach>
            </script>
            """)
    long countByKeyAndContacts(@Param("tenantId") Long tenantId, @Param("key") String key,
                               @Param("contactIds") List<Long> contactIds);
}