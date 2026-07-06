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

    /* ───────────────────── 주소 → 시/구 정규화 (준비용) ───────────────────── */

    /**
     * 자유형식 주소에서 '시도 + 시군구'만 뽑아 정규화한다.
     * 도로명/지번/상세주소는 버리고 행정구역 상위만 남긴다.
     *   예) "서울특별시 강남구 역삼동 테헤란로 123"  → "서울특별시 강남구"
     *       "경기도 성남시 분당구 정자동 45-6"       → "경기도 성남시 분당구"
     *       "부산 해운대구 우동"                     → "부산 해운대구"
     *
     * 아직 matchYouthPolicy 흐름에는 연결하지 않은 준비용 메서드다.
     * (주소 입력을 지원하려면 이 결과를 {@link #resolveRegionCodes(String)} 에 넘기면 된다)
     *
     * @return 정규화된 "시도 [시군구...]" 문자열, 추출 실패 시 원본을 trim 하여 반환
     */
    public String normalizeToSiGu(String address) {
        if (address == null || address.isBlank()) return "";

        List<String> parts = new ArrayList<>();
        for (String token : address.trim().split("\\s+")) {
            if (isSido(token)) {
                parts.clear();          // 시도가 나오면 그 앞의 잡토큰은 버리고 새로 시작
                parts.add(token);
            } else if (isSiGunGu(token) && !parts.isEmpty()) {
                parts.add(token);       // 시도 뒤에 오는 시/군/구 (성남시 분당구처럼 2단계도 누적)
            } else if (!parts.isEmpty()) {
                break;                  // 시/군/구 뒤 첫 비행정구역 토큰(동/도로명 등)에서 종료
            }
        }
        return parts.isEmpty() ? address.trim() : String.join(" ", parts);
    }

    /**
     * 주소를 시/구 단위로 정규화한 뒤 법정동코드를 조회하는 편의 메서드. (준비용)
     * 상세주소가 붙은 입력에도 대응하려면 {@link #resolveRegionCodes(String)} 대신 이걸 쓰면 된다.
     */
    public List<String> resolveRegionCodesByAddress(String address) {
        return resolveRegionCodes(normalizeToSiGu(address));
    }

    /** 시도 토큰 여부 (특별시/광역시/특별자치시/특별자치도/도, 축약형 '서울'·'부산' 등 포함). */
    private boolean isSido(String token) {
        if (token == null) return false;
        return token.endsWith("특별시") || token.endsWith("광역시")
                || token.endsWith("특별자치시") || token.endsWith("특별자치도")
                || token.endsWith("도")
                || SIDO_ABBR.contains(token);
    }

    /** 시/군/구 토큰 여부. (시도 축약형과 겹치지 않도록 축약형은 제외) */
    private boolean isSiGunGu(String token) {
        if (token == null || SIDO_ABBR.contains(token)) return false;
        return token.endsWith("시") || token.endsWith("군") || token.endsWith("구");
    }

    /** 시도 축약 표기 (사용자가 '서울 강남구'처럼 짧게 입력하는 경우). */
    private static final List<String> SIDO_ABBR = List.of(
            "서울", "부산", "대구", "인천", "광주", "대전", "울산", "세종",
            "경기", "강원", "충북", "충남", "전북", "전남", "경북", "경남", "제주"
    );
}
