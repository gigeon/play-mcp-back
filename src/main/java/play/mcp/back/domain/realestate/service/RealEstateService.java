package play.mcp.back.domain.realestate.service;

import play.mcp.back.domain.realestate.model.Deal;

import java.util.List;

/**
 * 실거래 조회 추상화. 구현체: {@link SampleRealEstateService}(번들 샘플),
 * {@link MolitRealEstateService}(국토부 실거래가 API).
 */
public interface RealEstateService {

    /**
     * @param region      지역명 (예: "서울", "서울 강남구")
     * @param housingType 주택유형 (아파트/오피스텔/빌라)
     * @param dealType    거래유형 (전세/월세/매매)
     * @return 해당 조건의 실거래 목록
     */
    List<Deal> findDeals(String region, String housingType, String dealType);
}
