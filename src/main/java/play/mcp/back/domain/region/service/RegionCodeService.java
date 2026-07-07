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

    @Value("${api.region.baseUrl}")
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
            // 법정동 API 는 JSON 본문을 text/html 로 내려주므로 Content-Type 무관 파싱을 쓴다.
            BaseMap resp = apiService.callGetAsMap(baseUrl, param);
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

    /* ───────────────────── 주소 정규화 (준비용) ───────────────────── */

    /**
     * 자유형식 주소에서 번지·도로명 등 상세주소를 떼어내고 행정구역(시/도·시/군/구·읍/면/동/리)만 남긴다.
     * 앞에서부터 행정구역 토큰을 모으다가, 숫자·도로명 등 행정구역이 아닌 토큰을 만나면 거기서 멈춘다.
     * 시도 축약형/옛 명칭은 법정동 API 가 인식하는 정식 명칭으로 확장한다(서울→서울특별시).
     *   예) "응암동 125-11"                     → "응암동"
     *       "서울 은평구 응암동 125-11"          → "서울특별시 은평구 응암동"
     *       "역삼동 테헤란로 123"                → "역삼동"
     *       "경기도 성남시 분당구 정자동 45-6"   → "경기도 성남시 분당구 정자동"
     *
     * {@link #resolveRegionCodesByAddress(String)} 를 통해 matchYouthPolicy·주택추천에서 사용된다.
     *
     * @return 상세주소를 제거한 행정구역 문자열, 추출 실패 시 원본을 trim 하여 반환
     */
    public String normalizeAddress(String address) {
        if (address == null || address.isBlank()) return "";

        List<String> parts = new ArrayList<>();
        for (String token : address.trim().split("\\s+")) {
            if (isAdminToken(token)) {
                parts.add(SIDO_FULL.getOrDefault(token, token));   // 시도 축약형/옛 명칭은 정식 명칭으로 확장
            } else if (!parts.isEmpty()) {
                break;                  // 행정구역 뒤 첫 상세주소(번지/도로명 등)에서 종료
            }
            // parts 가 비어있는 동안의 비행정구역 토큰(선행 잡토큰)은 건너뛴다
        }
        return parts.isEmpty() ? address.trim() : String.join(" ", parts);
    }

    /**
     * 주소를 정규화(상세주소 제거)한 뒤 법정동코드를 조회하는 편의 메서드.
     * 번지·도로명이 붙은 전체 주소도 처리되므로 호출부는 이 메서드를 쓴다.
     */
    public List<String> resolveRegionCodesByAddress(String address) {
        return resolveRegionCodes(normalizeAddress(address));
    }

    /** 토큰이 시도(축약형 또는 정식명)인지. (오타 보정에서 시도 판별용) */
    public boolean isSidoToken(String token) {
        return token != null && (SIDO_FULL.containsKey(token) || SIDO_FULL.containsValue(token));
    }

    /**
     * 특정 시도의 시군구 이름 목록을 법정동코드 API 에서 가져온다(오타 보정 후보용).
     * 응답 locatadd_nm("대구광역시 달서구 …")의 2번째 토큰(시군구)을 중복 없이 수집한다.
     */
    @SuppressWarnings("unchecked")
    public List<String> sigunguNamesOf(String sidoToken) {
        String full = SIDO_FULL.getOrDefault(sidoToken, sidoToken);
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();

        BaseMap param = new BaseMap();
        param.put("ServiceKey", serviceKey);
        param.put("pageNo", 1);
        param.put("numOfRows", 1000);
        param.put("type", "json");
        param.put("locatadd_nm", full);
        try {
            BaseMap resp = apiService.callGetAsMap(baseUrl, param);
            if (!(resp.get("StanReginCd") instanceof List<?> sections)) return List.of();
            for (Object section : sections) {
                if (!(section instanceof Map<?, ?> m)) continue;
                Object rows = ((Map<String, Object>) m).get("row");
                if (!(rows instanceof List<?> rowList)) continue;
                for (Object row : rowList) {
                    if (!(row instanceof Map<?, ?> r)) continue;
                    Object addr = ((Map<String, Object>) r).get("locatadd_nm");
                    if (addr == null) continue;
                    String[] tok = String.valueOf(addr).trim().split("\\s+");
                    if (tok.length >= 2 && !tok[1].isBlank()) names.add(tok[1]);
                }
            }
        } catch (Exception ignored) {
            // 실패 시 빈 목록 → 보정 생략
        }
        return new ArrayList<>(names);
    }

    /**
     * 행정구역 토큰 여부.
     * 시/도 축약형(서울·부산…) 또는 행정구역 접미사(도/시/군/구/읍/면/동/리/가)로 끝나면 참.
     * 도로명(로/길)·번지·숫자 토큰은 접미사가 걸리지 않아 자연히 제외된다.
     * ('역삼1동'처럼 숫자가 섞인 동 이름도 접미사로 판정되어 유지됨)
     */
    private boolean isAdminToken(String token) {
        if (token == null || token.isEmpty()) return false;
        if (SIDO_ABBR.contains(token)) return true;
        for (String suffix : ADMIN_SUFFIXES) {
            if (token.endsWith(suffix)) return true;
        }
        return false;
    }

    /** 행정구역 접미사 (긴 접미사 우선). */
    private static final List<String> ADMIN_SUFFIXES = List.of(
            "특별자치시", "특별자치도", "특별시", "광역시",
            "도", "시", "군", "구", "읍", "면", "동", "리", "가"
    );

    /** 시도 축약 표기 (사용자가 '서울 강남구'처럼 짧게 입력하는 경우). */
    private static final List<String> SIDO_ABBR = List.of(
            "서울", "부산", "대구", "인천", "광주", "대전", "울산", "세종",
            "경기", "강원", "충북", "충남", "전북", "전남", "경북", "경남", "제주"
    );

    /**
     * 시도 축약형/옛 명칭 → 법정동 API 정식 명칭.
     * (법정동 API 는 부분 명칭이라도 정식 표기여야 검색되며, 강원·전북은 개정 명칭만 인식한다)
     */
    private static final Map<String, String> SIDO_FULL = Map.ofEntries(
            Map.entry("서울", "서울특별시"), Map.entry("부산", "부산광역시"),
            Map.entry("대구", "대구광역시"), Map.entry("인천", "인천광역시"),
            Map.entry("광주", "광주광역시"), Map.entry("대전", "대전광역시"),
            Map.entry("울산", "울산광역시"), Map.entry("세종", "세종특별자치시"),
            Map.entry("경기", "경기도"), Map.entry("강원", "강원특별자치도"),
            Map.entry("충북", "충청북도"), Map.entry("충남", "충청남도"),
            Map.entry("전북", "전북특별자치도"), Map.entry("전남", "전라남도"),
            Map.entry("경북", "경상북도"), Map.entry("경남", "경상남도"),
            Map.entry("제주", "제주특별자치도"),
            // 개정 전 정식 명칭 별칭 (사용자가 옛 이름을 입력하는 경우)
            Map.entry("강원도", "강원특별자치도"), Map.entry("전라북도", "전북특별자치도")
    );
}
