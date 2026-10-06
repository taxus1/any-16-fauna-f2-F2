package com.somepro.infrastructure.persistence.obs;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.somepro.infrastructure.persistence.obs.po.WildlifeObsPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 野生动物观测记录的 MyBatis-Plus Mapper（基础设施层）。
 *
 * 阻塞（JDBC）API，只能在 boundedElastic 线程上调用（见 ObsTallyAdapter#blocking）。
 */
@Mapper
public interface WildlifeObsMapper extends BaseMapper<WildlifeObsPO> {
}
