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
        청년정책을 분야·중분류·키워드·지역으로 검색한다.
        요청이 막연하면("청년정책 알려줘") 바로 검색하지 말고 관심 분야/키워드/지역을 먼저 물어본 뒤, 조건이 하나라도 정해지면 검색한다.
        - lclsfNm/mclsfNm/keyword 는 각 파라미터의 '가능한 값' 목록 단어만 유효 — 사용자 자연어를 그 단어로 매핑해 넣어라(없는 임의어는 0건 위험). 예: "알바"→일자리, "집"→주거, "전세대출"→대출.
        - 여러 값은 콤마 구분, 값 안에는 콤마 금지("주택 및 거주지"는 하나의 토큰).
        - 상세는 결과 plcyNo 로 getPolicyDetail 호출.
        """)
    public BaseMap searchPolicy(
            @ToolParam(description = """
                정책 대분류. 목록의 단어만 사용. 여러 개면 콤마 구분(공백없이).
                가능한 값: 일자리 | 주거 | 교육 | 복지문화 | 참여권리
                예: "주거"  또는  "주거,일자리"
                """, required = false)
            String lclsfNm,
            @ToolParam(description = """
                정책 중분류. 목록의 단어만 사용(공백 포함 값은 통째로 하나의 토큰 — "주택,및,거주지" 아님).
                ※ 중요: mclsfNm 은 서버가 아닌 조회 결과에서 걸러지므로, 반드시 그 중분류가 속한 lclsfNm 도 함께 지정하라.
                  (예: mclsfNm="주택 및 거주지" → lclsfNm="주거" 함께 지정) lclsfNm 없이 mclsfNm 만 주면 결과가 누락된다.
                가능한 값: 취업 | 재직자 | 창업 | 주택 및 거주지 | 기숙사 |
                전월세 및 주거급여 지원 | 미래역량강화 | 교육비지원 | 온라인교육 |
                취약계층 및 금융지원 | 건강 | 예술인지원 | 문화활동 | 청년참여 |
                정책인프라구축 | 청년국제교류 | 권익보호
                여러 개면 콤마 구분. 예: "주택 및 거주지"  (O)   /   "주택,및,거주지" (X)
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
                지역 필터(선택). 사용자가 말한 지역 표현을 그대로 넣어라. 코드를 직접 계산하거나 지어내지 말 것.
                내부에서 법정시군구코드(5자리)로 자동 변환한다.
                '시/도 시/군/구' 형식이 가장 정확하다(예: "서울 강남구"). 동/번지가 붙어도 시군구 단위로 처리된다.
                여러 지역이면 콤마로 구분하고, 지역 조건이 없으면 생략한다.
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
        ZipResult zip = resolveZipCd(zipCd);                 // 지역명이면 법정동 시군구코드로 자동 변환
        putIfPresent(param, "zipCd", zip.codes());

        BaseMap resp = callGetPlcy(param);
        BaseMap out = slimResponse(filterMclsf ? filterByMclsfNm(resp, csv(mclsfNm)) : resp);
        if (!zip.ambiguous().isEmpty()) {
            out.put("regionWarning", "'" + String.join("', '", zip.ambiguous())
                    + "' 은(는) 여러 시/도에 있어 해당하는 지역을 모두 조회했습니다."
                    + " 특정 지역만 원하면 '서울 중구'처럼 시/도를 함께 알려주세요.");
        }
        if (housingRelated(lclsfNm, mclsfNm, keyword)) out.put("followUp", HOUSING_FOLLOW_UP);
        return out;
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
    /** zipCd 변환 결과: youthcenter 에 넘길 코드(codes) + 여러 시/도에 걸쳐 모호했던 지역명(ambiguous). */
    private record ZipResult(String codes, List<String> ambiguous) {}

    private ZipResult resolveZipCd(String zipCd) {
        if (zipCd == null || zipCd.isBlank()) return new ZipResult(null, List.of());
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        List<String> ambiguous = new ArrayList<>();
        for (String tok : zipCd.split(",")) {
            String t = tok.trim();
            if (t.isEmpty()) continue;
            if (t.matches("\\d{5}")) {
                out.add(t);
                continue;
            }
            List<String> codes = regionCodeService.resolveRegionCodesByAddress(t);
            // 시도만 입력(예: "서울")한 경우: 법정동 API 가 최대 100건만 주어 일부 구만 잡히는 문제가 있으므로,
            // 시도 대표 5자리 코드(예: 11000)로 처리한다. youthcenter 는 이를 '해당 시도 전체'로 인식한다.
            if (regionCodeService.isSidoToken(t) && !codes.isEmpty()) {
                out.add(codes.get(0).substring(0, 2) + "000");
                continue;
            }
            List<String> sigungu = codes.stream()
                    .filter(c -> c != null && c.length() == 10 && c.endsWith("00000"))
                    .toList();
            List<String> use = sigungu.isEmpty() ? codes : sigungu;   // 동 입력 등은 전체로 폴백
            // 같은 시군구명("중구")이 여러 시/도에 있으면(앞 2자리 시도코드가 2종 이상) 모호로 표시.
            long sidoCount = use.stream()
                    .filter(c -> c != null && c.length() >= 2)
                    .map(c -> c.substring(0, 2)).distinct().count();
            if (sidoCount > 1) ambiguous.add(t);
            for (String code : use) {
                if (code != null && code.length() >= 5) out.add(code.substring(0, 5));
            }
        }
        return new ZipResult(out.isEmpty() ? null : String.join(",", out), ambiguous);
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
        분야/키워드로 청년정책 후보를 조회한다(나이·결혼으로 서버 필터는 안 함).
        분야·키워드가 불명확하면 먼저 물어본다(나이·결혼여부도 받으면 결과를 걸러 안내 가능).
        반환된 각 정책의 지원연령(sprtTrgtMinAge~sprtTrgtMaxAge, sprtTrgtAgeLmtYn=N이면 무관)과
        결혼조건(mrgSttsCd: 0055001기혼/0055002미혼/0055003무관)을 보고 AI가 맞는 것만 골라 안내하라.
        최대 50건 반환 — 많으면 lclsfNm/keyword 로 좁혀 호출.
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
        BaseMap out = slimResponse(callGetPlcy(param));
        if (housingRelated(lclsfNm, keyword)) out.put("followUp", HOUSING_FOLLOW_UP);
        return out;
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
                if (one.isEmpty()) {
                    invalid.add(plcyNo);   // 조회 결과 없음 = 잘못된 정책번호
                } else {
                    one.forEach(p -> p.remove("zipCd"));   // 거대 필드 제거로 반환량 축소
                    policies.addAll(one);
                }
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

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /**
     * 주거 대분류 정책을 간단히(정책번호·정책명만) 최대 limit 건 반환한다.
     * 주거 추천 결과에 관련 정책을 가볍게 붙일 때 쓴다(다른 툴에서 호출).
     */
    public List<Map<String, Object>> topHousingPolicies(int limit) {
        BaseMap param = plcyParam();
        param.put("pageNum", "1");
        param.put("pageSize", String.valueOf(Math.max(1, limit)));
        param.put("lclsfNm", "주거");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> p : extractList(callGetPlcy(param))) {
            if (out.size() >= limit) break;
            Map<String, Object> brief = new java.util.LinkedHashMap<>();
            brief.put("plcyNo", p.get("plcyNo"));
            brief.put("plcyNm", p.get("plcyNm"));
            out.add(brief);
        }
        return out;
    }

    /* ───────────────────────── 후속 제안(툴 연결) ───────────────────────── */

    /** 주거 관련 검색이면 매물 추천으로 이어가도록 붙이는 제안 문구. */
    private static final String HOUSING_FOLLOW_UP =
            "주거 관련 정책이네요! 원하시면 관심 지역과 주택유형, 나이·연소득을 알려주세요. "
            + "대출 한도 안에서 실제 거래된 매물도 함께 추천해드릴 수 있어요(recommendHousing).";

    /** 대분류·중분류·키워드에 주거 관련 단어가 있으면 참. */
    private boolean housingRelated(String... fields) {
        for (String f : fields) {
            if (f == null) continue;
            if (f.contains("주거") || f.contains("주택") || f.contains("전세") || f.contains("월세")
                    || f.contains("전월세") || f.contains("임대") || f.contains("보증금")) {
                return true;
            }
        }
        return false;
    }

    /* ───────────────────────── 반환량 축소 ───────────────────────── */

    /** 목록 응답에서 각 정책에 남길 핵심 필드. 거대한 zipCd·긴 본문 등은 제외해 반환량을 줄인다. */
    private static final List<String> SLIM_FIELDS = List.of(
            "plcyNo", "plcyNm", "lclsfNm", "mclsfNm", "plcyKywdNm", "plcyExplnCn",
            "sprtTrgtMinAge", "sprtTrgtMaxAge", "sprtTrgtAgeLmtYn",
            "mrgSttsCd", "earnCndSeCd", "earnMinAmt", "earnMaxAmt",
            "aplyYmd", "aplyPrdSeCd", "sprvsnInstCdNm");

    /** 정책 한 건을 핵심 필드만 남겨 슬림화한다(목록 툴용). */
    private Map<String, Object> slim(Map<String, Object> p) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (String f : SLIM_FIELDS) {
            if (p.containsKey(f)) m.put(f, p.get(f));
        }
        return m;
    }

    /** 목록 툴이 한 번에 반환하는 최대 건수(반환량 상한). 더 필요하면 조건을 좁혀 재검색. */
    private static final int MAX_LIST = 30;

    /** 응답의 youthPolicyList 를 핵심 필드만 남기고 최대 {@link #MAX_LIST} 건으로 줄인다(목록 툴용). */
    @SuppressWarnings("unchecked")
    private BaseMap slimResponse(BaseMap resp) {
        if (resp.get("result") instanceof Map<?, ?> result) {
            Map<String, Object> r = (Map<String, Object>) result;
            if (r.get("youthPolicyList") instanceof List<?> list) {
                List<Map<String, Object>> slimmed = new ArrayList<>();
                for (Object o : list) {
                    if (slimmed.size() >= MAX_LIST) break;
                    if (o instanceof Map<?, ?> p) slimmed.add(slim((Map<String, Object>) p));
                }
                r.put("youthPolicyList", slimmed);
                r.put("returnedCount", slimmed.size());   // 실제 반환 건수(전체는 pagging.totCount)
            }
        }
        return resp;
    }

    /** 상세 응답에서 거대한 필드(zipCd: 대상 시군구 코드 수백 개)만 제거한다(상세 툴용). */
    private BaseMap stripHeavy(BaseMap resp) {
        for (Map<String, Object> p : extractList(resp)) {
            p.remove("zipCd");
        }
        return resp;
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
        return stripHeavy(resp);   // 거대한 zipCd 제거로 반환량 축소(신청방법 등 상세는 유지)
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

}
