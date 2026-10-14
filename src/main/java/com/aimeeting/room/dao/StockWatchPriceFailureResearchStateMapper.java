package com.aimeeting.room.dao;

import com.aimeeting.room.entity.StockWatchPriceFailureResearchState;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface StockWatchPriceFailureResearchStateMapper {
    StockWatchPriceFailureResearchState selectByRuleVersion(@Param("ruleVersion") String ruleVersion);

    int insert(StockWatchPriceFailureResearchState state);

    int updateCursor(@Param("ruleVersion") String ruleVersion,
                     @Param("rowVersion") Long rowVersion,
                     @Param("lastObservationId") Long lastObservationId);
}
