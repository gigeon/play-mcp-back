package play.mcp.back.tools;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import play.mcp.back.common.BaseMap;
import play.mcp.back.common.McpTool;
import play.mcp.back.domain.api.service.ApiService;
import play.mcp.back.domain.region.service.RegionCodeService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class YouthPoilyTool implements McpTool {

    private final ApiService apiService;
    private final RegionCodeService regionCodeService;

    @Value("${api.youth.baseUrl}")
    private String baseUrl;

    @Value("${api.youth.key}")
    private String apiKey;

    @Tool(description = """
        청년 정책을 대분류/중분류/키워드/지역 조건으로 검색한다.
        사용자가 특정 분야·조건의 청년 지원 정책 목록을 찾을 때 사용한다. 조건이 없으면 전체 검색.

        [입력 규칙 — 중요]
        - lclsfNm/mclsfNm/keyword 는 아래 '가능한 값' 목록에 있는 단어만 유효하다. 목록에 없는 임의어를 넣으면 결과가 0건이 될 수 있으니, 사용자의 자연어를 목록의 정확한 단어로 매핑해서 넣어라.
          (예: 사용자가 "전세대출" → keyword=대출, "집" → lclsfNm=주거, "알바" → lclsfNm=일자리)
        - 여러 값은 콤마(,)로 구분한다. 콤마 앞뒤 공백은 자동 제거되지만, 되도록 공백 없이 넣어라. (예: "주거,일자리")
        - 하나의 값 안에 콤마를 넣지 마라. "주택 및 거주지"처럼 공백이 포함된 값은 통째로 하나의 토큰이다("주택,및,거주지" 아님).
        - 상세가 필요하면 결과 plcyNo 로 getPolicyDetail 을 호출한다.
        """)
    public BaseMap searchPolicy(
            @ToolParam(description = """
                정책 대분류. 목록의 단어만 사용. 여러 개면 콤마 구분(공백없이).
                가능한 값: 일자리 | 주거 | 교육 | 복지문화 | 참여권리
                예: "주거"  또는  "주거,일자리"
                """, required = false)
            String lclsfNm,
            @ToolParam(description = """
                정책 중분류. 목록의 단어만 사용(공백 포함 값은 통째로 하나). 여러 개면 콤마 구분.
                가능한 값: 취업 | 재직자 | 창업 | 주택 및 거주지 | 기숙사 |
                전월세 및 주거급여 지원 | 미래역량강화 | 교육비지원 | 온라인교육 |
                취약계층 및 금융지원 | 건강 | 예술인지원 | 문화활동 | 청년참여 |
                정책인프라구축 | 청년국제교류 | 권익보호
                예: "주택 및 거주지"  (O)   /   "주택,및,거주지" (X)
                """, required = false)
            String mclsfNm,
            @ToolParam(description = """
                정책 키워드 태그. 목록의 단어만 사용. 여러 개면 콤마 구분(공백없이).
                가능한 값: 대출 | 보조금 | 바우처 | 금리혜택 | 교육지원 |
                맞춤형상담서비스 | 인턴 | 벤처 | 중소기업 | 청년가장 |
                장기미취업청년 | 공공임대주택 | 신용회복 | 육아 | 출산 | 해외진출 | 주거지원
                예: "대출"  또는  "대출,보조금"
                """, required = false)
            String keyword,
            @ToolParam(description = """
                지역. 지역명 또는 법정시군구코드 5자리. 지역명은 법정동코드로 자동 변환된다.
                지역명은 '시/도 + 시/군/구'를 함께 주면 정확하다. (동/번지는 시군구로 처리됨)
                예: "서울 강남구" | "대구 수성구" | "11680"  · 여러 개면 콤마 구분 · 모르면 생략
                """, required = false)
            String zipCd
    ) {
        boolean filterMclsf = mclsfNm != null && !mclsfNm.isBlank();
        BaseMap param = plcyParam();
        param.put("pageNum", "1");
        // 중분류는 API 가 공백 포함 값을 필터 못 해 클라이언트에서 거르므로 넉넉히 받는다.
        param.put("pageSize", filterMclsf ? "100" : "10");
        putIfPresent(param, "lclsfNm", csv(lclsfNm));        // "일자리, 주거" → "일자리,주거" (콤마 뒤 공백 제거)
        putIfPresent(param, "plcyKywdNm", csv(keyword));
        putIfPresent(param, "zipCd", resolveZipCd(zipCd));   // 지역명이면 법정동 시군구코드로 자동 변환

        BaseMap resp = callGetPlcy(param);
        return filterMclsf ? filterByMclsfNm(resp, csv(mclsfNm)) : resp;
    }

    /** 콤마 구분 값의 각 토큰 공백을 제거하고 빈 토큰을 버린다. "일자리, 주거" → "일자리,주거". */
    private static String csv(String v) {
        if (v == null || v.isBlank()) return null;
        List<String> parts = new ArrayList<>();
        for (String t : v.split(",")) {
            if (!t.trim().isEmpty()) parts.add(t.trim());
        }
        return parts.isEmpty() ? null : String.join(",", parts);
    }

    /**
     * zipCd 입력이 지역명이면 법정동 API 로 시군구 5자리 코드로 변환한다. 5자리 숫자는 그대로 둔다.
     * <p>지역명은 <b>시군구 단위(region_cd 끝 5자리 00000)</b>만 취해, "안산"이 타 지역 "안산동"까지
     * 부분매칭으로 잡는 것을 배제한다. 그 결과 "안산"과 "안산시"가 동일한 결과를 낸다.
     * (시군구 단위 매칭이 없으면 — 예: 동 이름 입력 — 전체 매칭의 앞 5자리로 폴백한다.)</p>
     */
    private String resolveZipCd(String zipCd) {
        if (zipCd == null || zipCd.isBlank()) return null;
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (String tok : zipCd.split(",")) {
            String t = tok.trim();
            if (t.isEmpty()) continue;
            if (t.matches("\\d{5}")) {
                out.add(t);
                continue;
            }
            List<String> codes = regionCodeService.resolveRegionCodesByAddress(t);
            List<String> sigungu = codes.stream()
                    .filter(c -> c != null && c.length() == 10 && c.endsWith("00000"))
                    .toList();
            List<String> use = sigungu.isEmpty() ? codes : sigungu;   // 동 입력 등은 전체로 폴백
            for (String code : use) {
                if (code != null && code.length() >= 5) out.add(code.substring(0, 5));
            }
        }
        return out.isEmpty() ? null : String.join(",", out);
    }

    /** 응답의 youthPolicyList 를 mclsfNm(콤마구분, 부분일치)으로 거르고 pagging.totCount 를 갱신한다. */
    @SuppressWarnings("unchecked")
    private BaseMap filterByMclsfNm(BaseMap resp, String mclsfNm) {
        String[] wants = mclsfNm.split(",");
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> p : extractList(resp)) {
            String v = str(p.get("mclsfNm"));
            if (v == null) continue;
            for (String w : wants) {
                if (!w.trim().isEmpty() && v.contains(w.trim())) {
                    filtered.add(p);
                    break;
                }
            }
        }
        if (resp.get("result") instanceof Map<?, ?> result) {
            Map<String, Object> r = (Map<String, Object>) result;
            r.put("youthPolicyList", filtered);
            if (r.get("pagging") instanceof Map<?, ?> pg) {
                ((Map<String, Object>) pg).put("totCount", filtered.size());
            }
        }
        return resp;
    }

    @Tool(description = """
        청년 본인의 나이나 결혼여부에 맞는 정책을 찾을 때 사용한다.
        분야나 키워드로 정책을 조회한 뒤, 응답의 지원연령(sprtTrgtMinAge~sprtTrgtMaxAge)과
        결혼조건(mrgSttsCd)을 보고 사용자 조건에 맞는 정책만 골라 안내한다.
        (mrgSttsCd: 0055001=기혼, 0055002=미혼, 0055003=제한없음.
         sprtTrgtAgeLmtYn=N 이면 연령 제한 없음)
        """)
    public BaseMap findMyPolicy(
            @ToolParam(description = """
                관심 분야 대분류(선택). 아래 목록의 단어만 유효 — 사용자 자연어를 목록 단어로 매핑해 넣어라.
                가능한 값: 일자리 | 주거 | 교육 | 복지문화 | 참여권리
                여러 개면 콤마 구분(공백없이). 예: "주거" 또는 "주거,교육"
                """, required = false)
            String lclsfNm,
            @ToolParam(description = """
                관심 키워드(선택). 정책명(plcyNm) 부분검색이라 목록에 없는 자유어도 가능하다.
                예: 장학금 | 전세 | 월세 | 면접  (한 단어 위주로 넣는 게 정확)
                """, required = false)
            String keyword
    ) {
        BaseMap param = plcyParam();
        param.put("pageNum", "1");
        param.put("pageSize", "50");
        putIfPresent(param, "lclsfNm", lclsfNm);
        // plcyKywdNm(태그 정확일치)은 '장학금'처럼 태그가 없으면 0건이 되므로 plcyNm(정책명 부분검색)을 쓴다.
        putIfPresent(param, "plcyNm", keyword);
        return callGetPlcy(param);
    }

    @Tool(description = """
        정책 번호(plcyNo)로 정책 하나의 전체 상세 정보를 조회한다.
        검색이나 맞춤조회로 얻은 정책번호를 넣어 사용한다.
        응답 코드값 의미: mrgSttsCd(결혼) 0055001=기혼/0055002=미혼/0055003=제한없음,
        earnCndSeCd(소득) 0043001=무관/0043002=연소득/0043003=기타,
        aplyPrdSeCd(신청기간) 0057001=특정기간/0057002=상시/0057003=마감.
        """)
    public BaseMap getPolicyDetail(
            @ToolParam(description = "정책 번호") String plcyNo
    ) {
        return policyDetailOrError(plcyNo);
    }

    @Tool(description = """
        여러 정책을 한 번에 비교할 때 사용한다. 콤마로 구분한 정책번호들을 받아
        각각의 상세를 조회해 목록으로 반환하므로, AI가 지원내용·연령·소득조건 등 차이를 정리한다.
        정책번호 하나만 넣으면 그 정책 상세만 반환한다.
        """)
    public BaseMap comparePolicy(
            @ToolParam(description = "비교할 정책 번호들. 여러 개면 콤마로 구분 (예: 2026...,2026...)") String plcyNos
    ) {
        List<Map<String, Object>> policies = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        if (plcyNos != null) {
            for (String no : plcyNos.split(",")) {
                String plcyNo = no.trim();
                if (plcyNo.isEmpty()) continue;
                if (!isValidPlcyNo(plcyNo)) { invalid.add(plcyNo); continue; }   // 형식 오류(특수문자 등)
                List<Map<String, Object>> one = extractList(policyDetail(plcyNo));
                if (one.isEmpty()) invalid.add(plcyNo);   // 조회 결과 없음 = 잘못된 정책번호
                else policies.addAll(one);
            }
        }
        BaseMap out = new BaseMap();
        out.put("comparedCount", policies.size());
        out.put("policies", policies);
        if (!invalid.isEmpty()) {
            out.put("invalidPlcyNos", invalid);
            out.put("message", "제대로된 정책번호가 아닙니다: " + String.join(", ", invalid));
        }
        return out;
    }

    @Tool(description = """
        정책의 신청 방법을 안내할 때 사용한다. 정책번호로 상세를 조회하며,
        응답의 신청방법(plcyAplyMthdCn), 제출서류(sbmsnDcmntCn),
        신청URL(aplyUrlAddr), 심사방법(srngMthdCn)을 참고해 안내한다.
        """)
    public BaseMap getApplyGuide(
            @ToolParam(description = "정책 번호") String plcyNo
    ) {
        return policyDetailOrError(plcyNo);
    }

    @Tool(description = """
        나이·거주지 조건으로 '신청 가능성이 있는' 청년정책만 골라준다.
        전국(중앙부처) 정책과 거주지 지자체 정책을 합쳐 나이로 필터링한다.
        지자체 정책은 거주기간 등 추가 요건이 있을 수 있어 결과는 '후보'로 안내한다.

        거주지는 행정안전부 법정동코드 API 로 코드 변환 후, 정책의 대상 지역코드(zipCd)에
        '포함'되는지로 판정한다. (예: 역삼동 → 강남구 정책 매칭)
        번지·도로명이 붙은 전체 주소를 넣어도 행정구역만 추려서 처리한다.

        Args:
          age: 만 나이
          region: (선택) 주민등록상 거주지 - 동/구/시 이름 또는 전체 주소
                  (예: 역삼동, 강남구, 서울특별시 강남구, "서울 은평구 응암동 125-11")
          lclsfNm: (선택) 정책대분류로 범위 축소
        """)
    public BaseMap matchYouthPolicy(
            @ToolParam(description = "만 나이") int age,
            @ToolParam(required = false, description = "거주지 - 동/구/시 이름 또는 전체 주소 (예: 역삼동, 서울 은평구 응암동 125-11)") String region,
            @ToolParam(required = false, description = "정책대분류: 일자리/주거/교육/복지문화/참여권리")
            String lclsfNm
    ) {
        BaseMap p = plcyParam();     // 넓게 받아서 조건은 서버에서 필터링
        p.put("pageSize", "50");
        putIfPresent(p, "lclsfNm", lclsfNm);
        BaseMap resp = callGetPlcy(p);

        // 거주지 → 법정동코드(10자리) 변환 (한 번만 조회해서 재사용)
        // 번지·도로명이 붙은 전체 주소를 넣어도 행정구역만 추려서 조회한다.
        List<String> userCodes = regionCodeService.resolveRegionCodesByAddress(region);

        List<Map<String, Object>> matched = new ArrayList<>();
        for (Map<String, Object> it : extractList(resp)) {
            if (ageMatches(it, age) && regionMatches(it, region, userCodes)) {
                matched.add(it);
            }
        }

        BaseMap out = new BaseMap();
        out.put("condition", "만 " + age + "세"
                + (region != null && !region.isBlank() ? " / " + region + " 거주" : ""));
        if (region != null && !region.isBlank()) {
            out.put("resolvedRegionCodes", userCodes);   // 법정동코드 변환 결과(투명성/디버깅용)
        }
        out.put("matchedCount", matched.size());
        out.put("policies", matched);
        out.put("notice", "지자체 정책은 거주기간 등 추가 요건이 있을 수 있습니다. 실제 신청 전 상세 요건을 확인하세요.");
        return out;
    }

    /** 나이 조건 매칭. 연령 정보가 없으면 통과시킨다. */
    private boolean ageMatches(Map<String, Object> it, int age) {
        Integer lo = parseInt(it.get("sprtTrgtMinAge"));
        Integer hi = parseInt(it.get("sprtTrgtMaxAge"));
        if (lo == null && hi == null) return true;                 // 연령 정보 없음 → 후보 포함
        if (lo != null && age < lo) return false;
        if (hi != null && hi > 0 && age > hi) return false;
        return true;
    }

    private Integer parseInt(Object o) {
        if (o == null) return null;
        try {
            return Integer.parseInt(str(o).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /* ───────────────────────── 요청 헬퍼 ───────────────────────── */

    /** 온통청년 API 공통 파라미터(인증키 + JSON 응답). */
    private BaseMap plcyParam() {
        BaseMap p = new BaseMap();
        p.put("apiKeyNm", apiKey);
        p.put("rtnType", "json");
        return p;
    }

    /** 값이 있을 때만 파라미터에 추가한다. */
    private void putIfPresent(BaseMap param, String key, String value) {
        if (value != null && !value.isBlank()) param.put(key, value);
    }

    /** getPlcy 엔드포인트 호출. */
    private BaseMap callGetPlcy(BaseMap param) {
        return apiService.callGet(baseUrl + "/getPlcy", param);
    }

    /** 정책번호 하나의 상세(pageType=2) 조회. detail/compare/applyGuide 공통. */
    private BaseMap policyDetail(String plcyNo) {
        BaseMap param = plcyParam();
        param.put("plcyNo", plcyNo);
        param.put("pageType", "2");
        return callGetPlcy(param);
    }

    /** 정책번호 형식(숫자 20자리)이 맞는지. 특수문자·공백·자릿수 오류를 API 호출 전에 거른다. */
    private static boolean isValidPlcyNo(String plcyNo) {
        return plcyNo != null && plcyNo.trim().matches("\\d{15,20}");
    }

    /** 형식 검증 → 상세 조회 → 결과 없으면 에러. 잘못된 정책번호는 명확히 안내한다. */
    private BaseMap policyDetailOrError(String plcyNo) {
        if (!isValidPlcyNo(plcyNo)) {
            BaseMap err = new BaseMap();
            err.put("plcyNo", plcyNo);
            err.put("error", "정책번호 형식이 올바르지 않습니다. 숫자 20자리를 입력하세요: " + plcyNo);
            return err;
        }
        BaseMap resp = policyDetail(plcyNo.trim());
        if (extractList(resp).isEmpty()) {
            BaseMap err = new BaseMap();
            err.put("plcyNo", plcyNo);
            err.put("error", "제대로된 정책번호가 아닙니다: " + plcyNo);
            return err;
        }
        return resp;
    }

    /* ───────────────────────── 파싱 헬퍼 ───────────────────────── */

    /** 응답 BaseMap 에서 정책 목록(result.youthPolicyList)을 방어적으로 꺼낸다. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractList(BaseMap resp) {
        if (resp == null) return new ArrayList<>();
        try {
            Object result = resp.get("result");
            if (result instanceof Map<?, ?> r) {
                Object list = ((Map<String, Object>) r).get("youthPolicyList");
                if (list instanceof List<?> l) {
                    return (List<Map<String, Object>>) (List<?>) l;
                }
            }
            Object direct = resp.get("youthPolicyList");   // 혹시 최상위에 있는 경우
            if (direct instanceof List<?> l) {
                return (List<Map<String, Object>>) (List<?>) l;
            }
        } catch (Exception ignored) {
            // 구조가 다르면 빈 목록 반환 (실제 응답 확인 후 경로 조정)
        }
        return new ArrayList<>();
    }

    private boolean regionMatches(Map<String, Object> it, String region, List<String> userCodes) {
        if (region == null || region.isBlank()) return true;
        if ("0054001".equals(str(it.get("pvsnInstGroupCd")))) return true;  // 중앙부처=전국

        if (userCodes != null && !userCodes.isEmpty()) {
            String zip = str(it.get("zipCd"));
            if (zip == null || zip.isBlank()) return false;   // 지역코드 없는 지자체 정책은 확정 불가 → 제외
            for (String token : zip.split(",")) {
                String t = token.trim();
                if (t.isEmpty()) continue;
                for (String uc : userCodes) {
                    if (uc != null && uc.startsWith(t)) return true;
                }
            }
            return false;
        }

        // 폴백: 법정동코드 변환 실패 → 기관명 텍스트 매칭
        for (String key : new String[]{"rgtrInstCdNm", "operInstCdNm", "sprvsnInstCdNm"}) {
            String v = str(it.get(key));
            if (v != null && v.contains(region)) return true;
        }
        return false;
    }
}
