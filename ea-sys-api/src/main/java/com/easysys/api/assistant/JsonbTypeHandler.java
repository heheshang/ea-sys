package com.easysys.api.assistant;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

/**
 * jsonb 列 TypeHandler：写侧把 JSON 文本以 {@code Types.OTHER}（未知类型）发送，
 * 由 PostgreSQL 驱动/服务器隐式转为 jsonb（MyBatis 默认按 varchar 绑定会报
 * “column is of type jsonb but expression is of type character varying”）。
 * 读侧 jsonb 直接按字符串取回。
 */
public class JsonbTypeHandler extends BaseTypeHandler<String> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setObject(i, parameter, Types.OTHER);
    }

    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return rs.getString(columnName);
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return rs.getString(columnIndex);
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return cs.getString(columnIndex);
    }
}