package com.easysys.api.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.easysys.api.entity.EvaluationDatasetVersion;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 数据集版本：BaseMapper 覆盖租户过滤/逻辑删除（与 EvaluationDatasetMapper 同策略）；
 * 自定义 nextVersionNo 绕过租户插件（dataset_id 全局唯一即可，无需租户过滤）。
 */
public interface EvaluationDatasetVersionMapper extends BaseMapper<EvaluationDatasetVersion> {

    /** 数据集内下一版本号：全行（含逻辑删除）MAX+1，保证 UNIQUE(tenant_id,dataset_id,version_no) 无碰撞、版本号单调递增不复用。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT COALESCE(MAX(version_no), 0) + 1 FROM evaluation_dataset_version WHERE dataset_id = #{datasetId}")
    Integer nextVersionNo(@Param("datasetId") Long datasetId);
}