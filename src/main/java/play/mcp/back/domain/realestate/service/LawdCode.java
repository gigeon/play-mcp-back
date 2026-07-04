package play.mcp.back.domain.realestate.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 지역명 → 법정동코드(LAWD_CD, 시군구 5자리) 매핑.
 *
 * <p>MOLIT 실거래가 API 는 LAWD_CD 를 필수로 요구한다. 시도(서울/인천/경기 등)를 먼저 판별한 뒤
 * 그 시도 안에서 시군구를 찾는다. 그래서 "인천 중구"가 서울 "중구"로 오매칭되지 않는다.
 * 5자리 숫자를 직접 주면 그대로 코드로 사용한다. "서울"처럼 구가 없으면 시도 대표값으로 폴백한다.</p>
 */
public final class LawdCode {

    private LawdCode() {
    }

    /** 시도명 → (시군구명 → 5자리 코드). LinkedHashMap 으로 입력 순서 유지. */
    private static final Map<String, Map<String, String>> SIDO = new LinkedHashMap<>();
    /** 시도만 주어졌을 때 대표 시군구 코드. */
    private static final Map<String, String> SIDO_DEFAULT = new LinkedHashMap<>();

    static {
        Map<String, String> seoul = new LinkedHashMap<>();
        seoul.put("종로구", "11110"); seoul.put("중구", "11140"); seoul.put("용산구", "11170");
        seoul.put("성동구", "11200"); seoul.put("광진구", "11215"); seoul.put("동대문구", "11230");
        seoul.put("중랑구", "11260"); seoul.put("성북구", "11290"); seoul.put("강북구", "11305");
        seoul.put("도봉구", "11320"); seoul.put("노원구", "11350"); seoul.put("은평구", "11380");
        seoul.put("서대문구", "11410"); seoul.put("마포구", "11440"); seoul.put("양천구", "11470");
        seoul.put("강서구", "11500"); seoul.put("구로구", "11530"); seoul.put("금천구", "11545");
        seoul.put("영등포구", "11560"); seoul.put("동작구", "11590"); seoul.put("관악구", "11620");
        seoul.put("서초구", "11650"); seoul.put("강남구", "11680"); seoul.put("송파구", "11710");
        seoul.put("강동구", "11740");
        SIDO.put("서울", seoul);
        SIDO_DEFAULT.put("서울", "11680");

        Map<String, String> incheon = new LinkedHashMap<>();
        incheon.put("중구", "28110"); incheon.put("동구", "28140"); incheon.put("미추홀구", "28177");
        incheon.put("연수구", "28185"); incheon.put("남동구", "28200"); incheon.put("부평구", "28237");
        incheon.put("계양구", "28245");
        // 2026년 인천 서구가 서해구(청라·가정)·검단구(검단신도시)로 분구. 옛 서구(28260)는 폐지되어 데이터 없음.
        incheon.put("서해구", "28275"); incheon.put("검단구", "28290");
        incheon.put("강화군", "28710"); incheon.put("옹진군", "28720");
        SIDO.put("인천", incheon);
        SIDO_DEFAULT.put("인천", "28185"); // 연수구

        Map<String, String> busan = new LinkedHashMap<>();
        busan.put("중구", "26110"); busan.put("서구", "26140"); busan.put("동구", "26170");
        busan.put("영도구", "26200"); busan.put("부산진구", "26230"); busan.put("동래구", "26260");
        busan.put("남구", "26290"); busan.put("북구", "26320"); busan.put("해운대구", "26350");
        busan.put("사하구", "26380"); busan.put("금정구", "26410"); busan.put("강서구", "26440");
        busan.put("연제구", "26470"); busan.put("수영구", "26500"); busan.put("사상구", "26530");
        busan.put("기장군", "26710");
        SIDO.put("부산", busan);
        SIDO_DEFAULT.put("부산", "26350"); // 해운대구
    }

    /**
     * 지역 문자열에서 법정동코드를 찾는다.
     * 5자리 숫자면 그대로, 시도+시군구면 해당 시도 내 시군구, 시도만이면 대표값.
     *
     * @return 5자리 LAWD_CD, 매핑 불가 시 {@code null}
     */
    public static String resolve(String region) {
        if (region == null || region.isBlank()) {
            return null;
        }
        String r = region.trim();
        if (r.matches("\\d{5}")) {
            return r;
        }

        // 1) 시도 판별
        String sido = null;
        for (String s : SIDO.keySet()) {
            if (r.contains(s)) {
                sido = s;
                break;
            }
        }

        // 2) 시도가 특정되면 그 안에서 시군구 검색 → 없으면 시도 대표값
        if (sido != null) {
            for (Map.Entry<String, String> e : SIDO.get(sido).entrySet()) {
                if (r.contains(e.getKey())) {
                    return e.getValue();
                }
            }
            return SIDO_DEFAULT.get(sido);
        }

        // 3) 시도 없이 시군구만 준 경우(예: "강남구") — 전 시도에서 검색
        for (Map<String, String> gu : SIDO.values()) {
            for (Map.Entry<String, String> e : gu.entrySet()) {
                if (r.contains(e.getKey())) {
                    return e.getValue();
                }
            }
        }
        return null;
    }
}
