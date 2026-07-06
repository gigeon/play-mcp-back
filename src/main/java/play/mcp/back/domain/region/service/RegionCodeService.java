package play.mcp.back.domain.region.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import play.mcp.back.common.BaseMap;
import play.mcp.back.domain.api.service.ApiService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 행정안전부 행정표준코드 법정동코드 API 래퍼.
 * 지역주소명(예: "역삼동", "강남구", "서울특별시 강남구")으로 10자리 법정동코드(region_cd)를 조회한다.
 *
 * 엔드포인트: /1741000/StanReginCd/getStanReginCdList
 * 응답 구조: { "StanReginCd": [ { "head":[...] }, { "row":[ { region_cd, locatadd_nm, ... } ] } ] }
 *
 * ── 실제 응답으로 검증(2026-07-06, curl 확인)
 *  - locatadd_nm=역삼동 → region_cd=1168010100 (서울 강남구 역삼동)
 *  - region_cd 앞 5자리(1168·0)가 청년정책 zipCd 의 시군구 코드(콤마구분)와 대응한다.
 */
@Service
@RequiredArgsConstructor
public class RegionCodeService {

    private final ApiService apiService;

    @Value("${api.region.base-url}")
    private String baseUrl;

    @Value("${api.region.key}")
    private String serviceKey;

    /**
     * 지역주소명으로 법정동코드(region_cd, 10자리) 목록을 조회한다.
     * 조회 실패/무결과 시 빈 목록을 반환한다(호출부에서 폴백 처리).
     */
    @SuppressWarnings("unchecked")
    public List<String> resolveRegionCodes(String locataddNm) {
        List<String> codes = new ArrayList<>();
        if (locataddNm == null || locataddNm.isBlank()) return codes;

        BaseMap param = new BaseMap();
        param.put("ServiceKey", serviceKey);
        param.put("pageNo", 1);
        param.put("numOfRows", 100);
        param.put("type", "json");
        param.put("locatadd_nm", locataddNm.trim());

        try {
            BaseMap resp = apiService.callGet(baseUrl, param);
            if (resp == null) return codes;

            Object stan = resp.get("StanReginCd");
            if (!(stan instanceof List<?> sections)) return codes;

            for (Object section : sections) {
                if (!(section instanceof Map<?, ?> m)) continue;
                Object rows = ((Map<String, Object>) m).get("row");
                if (rows instanceof List<?> rowList) {
                    for (Object row : rowList) {
                        if (row instanceof Map<?, ?> r) {
                            Object code = ((Map<String, Object>) r).get("region_cd");
                            if (code != null) codes.add(String.valueOf(code));
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // 조회 실패 시 빈 목록 → 호출부에서 기관명 텍스트 매칭으로 폴백
        }
        return codes;
    }
}
