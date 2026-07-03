package play.mcp.back.tools;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import play.mcp.back.common.BaseMap;
import play.mcp.back.domain.api.service.ApiService;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class YouthPoilyTool {

    private final ApiService apiService;

    @Value("${api.baseUrl}")
    private String baseUrl;

    @Value("${api.key}")
    private String key;

    @Tool(description = """
        청년 정책을 대분류, 중분류, 키워드, 지역 조건으로 검색한다.
        사용자가 특정 분야나 조건의 청년 지원 정책 목록을 찾을 때 사용한다.
        여러 조건을 동시에 걸 수 있으며, 조건이 없으면 전체에서 검색한다.
        상세 정보가 필요하면 결과의 정책번호로 getPolicyDetail을 사용한다.
        """)
    public BaseMap searchPolicy(
            @ToolParam(description = """
                정책 대분류명. 여러 개면 콤마로 구분.
                가능한 값: 일자리 / 주거 / 교육 / 복지문화 / 참여권리
                """, required = false)
            String lclsfNm,
            @ToolParam(description = """
                정책 중분류명. 여러 개면 콤마로 구분.
                가능한 값: 취업 / 재직자 / 창업 / 주택 및 거주지 / 기숙사 /
                전월세 및 주거급여 지원 / 미래역량강화 / 교육비지원 / 온라인교육 /
                취약계층 및 금융지원 / 건강 / 예술인지원 / 문화활동 / 청년참여 /
                정책인프라구축 / 청년국제교류 / 권익보호
                """, required = false)
            String mclsfNm,
            @ToolParam(description = """
                정책 키워드명. 여러 개면 콤마로 구분.
                가능한 값: 대출 / 보조금 / 바우처 / 금리혜택 / 교육지원 /
                맞춤형상담서비스 / 인턴 / 벤처 / 중소기업 / 청년가장 /
                장기미취업청년 / 공공임대주택 / 신용회복 / 육아 / 출산 /
                해외진출 / 주거지원
                """, required = false)
            String keyword,
            @ToolParam(description = """
                법정시군구코드 5자리. 여러 개면 콤마로 구분.
                시/도 예: 서울=11000, 부산=26000, 대구=27000, 인천=28000,
                광주=29000, 대전=30000, 울산=31000, 경기=41000.
                모르면 생략한다.
                """, required = false)
            String zipCd
    ) {
        BaseMap param = baseParam();
        param.put("pageNum", "1");
        param.put("pageSize", "10");
        if (lclsfNm != null) param.put("lclsfNm", lclsfNm);
        if (mclsfNm != null) param.put("mclsfNm", mclsfNm);
        if (keyword != null) param.put("plcyKywdNm", keyword);
        if (zipCd != null)   param.put("zipCd", zipCd);

        List<BaseMap> policies = fetchList(param);
        List<BaseMap> summary = policies.stream().map(this::toSummary).toList();

        BaseMap out = new BaseMap();
        out.put("검색결과수", summary.size());
        out.put("정책목록", summary);
        return out;
    }

    @Tool(description = """
        청년 본인의 조건(나이, 결혼여부)에 맞아 신청 가능한 정책만 골라준다.
        API가 나이로 필터해주지 않으므로 검색 후 응답의 연령/결혼 조건으로 직접 걸러낸다.
        사용자가 "나 O살인데 받을 수 있는 정책"처럼 물을 때 사용한다.
        결혼여부는 정책의 mrgSttsCd로 판단한다 (0055003=제한없음이면 누구나 가능).
        """)
    public BaseMap findMyPolicy(
            @ToolParam(description = "나이 (숫자)")
            Integer age,
            @ToolParam(description = "결혼여부. 기혼 / 미혼 중 하나. 모르면 생략.", required = false)
            String marriage,
            @ToolParam(description = """
                관심 분야 대분류명 (선택).
                일자리 / 주거 / 교육 / 복지문화 / 참여권리
                """, required = false)
            String lclsfNm,
            @ToolParam(description = "관심 키워드 (선택)", required = false)
            String keyword
    ) {
        BaseMap param = baseParam();
        param.put("pageNum", "1");
        param.put("pageSize", "50");   // 넉넉히 받아 필터
        if (lclsfNm != null) param.put("lclsfNm", lclsfNm);
        if (keyword != null) param.put("plcyKywdNm", keyword);

        List<BaseMap> policies = fetchList(param);

        List<BaseMap> matched = policies.stream()
                .filter(p -> ageMatches(p, age))
                .filter(p -> marriageMatches(p, marriage))
                .map(this::toSummary)
                .toList();

        BaseMap out = new BaseMap();
        out.put("입력조건", "나이 " + age + (marriage != null ? ", " + marriage : ""));
        out.put("맞춤정책수", matched.size());
        out.put("정책목록", matched);
        return out;
    }

    @Tool(description = """
        정책 번호(plcyNo)로 정책 하나의 전체 상세 정보를 조회한다.
        검색이나 맞춤필터로 얻은 정책번호를 넣어 사용한다.
        응답에 코드값(mrgSttsCd, earnCndSeCd, jobCd, schoolCd 등)이 포함되며,
        각 코드의 의미는 이 클래스 상단의 코드 해석표를 참고한다.
        """)
    public BaseMap getPolicyDetail(
            @ToolParam(description = "정책 번호") String plcyNo
    ) {
        BaseMap p = fetchOne(plcyNo);
        if (p == null) return notFound(plcyNo);
        return toDetail(p);
    }

    @Tool(description = """
        여러 정책을 나란히 비교할 수 있도록 주요 정보를 정리해 반환한다.
        지원내용, 연령, 소득조건 등 차이를 확인할 때 사용한다. 한 번에 최대 4개.
        응답의 코드값 의미는 이 클래스 상단의 코드 해석표를 참고한다.
        """)
    public BaseMap comparePolicy(
            @ToolParam(description = "비교할 정책 번호들 (콤마 구분)") String plcyNos
    ) {
        String[] nos = plcyNos.split(",");
        if (nos.length > 4) {
            BaseMap out = new BaseMap();
            out.put("error", "한 번에 최대 4개까지 비교할 수 있습니다.");
            return out;
        }

        List<BaseMap> compared = new ArrayList<>();
        for (String no : nos) {
            BaseMap p = fetchOne(no.trim());
            if (p == null) continue;
            compared.add(new BaseMap()
                    .set("정책번호", p.get("plcyNo"))
                    .set("정책명", p.get("plcyNm"))
                    .set("지원내용", p.get("plcySprtCn"))
                    .set("지원연령", ageRange(p))
                    .set("결혼조건코드", p.get("mrgSttsCd"))
                    .set("소득조건코드", p.get("earnCndSeCd"))
                    .set("신청기간", p.get("bizPrdEtcCn"))
                    .set("운영기관", p.get("operInstCdNm")));
        }

        BaseMap out = new BaseMap();
        out.put("비교대상수", compared.size());
        out.put("정책비교", compared);
        return out;
    }

    @Tool(description = """
        정책 번호로 신청 방법에 초점을 맞춰 안내한다.
        신청 절차, 제출 서류, 신청 URL, 심사 방법을 정리해 반환한다.
        사용자가 "이거 어떻게 신청해?"처럼 물을 때 사용한다.
        aplyPrdSeCd(신청기간구분) 코드 의미는 상단 코드 해석표 참고.
        """)
    public BaseMap getApplyGuide(
            @ToolParam(description = "정책 번호") String plcyNo
    ) {
        BaseMap p = fetchOne(plcyNo);
        if (p == null) return notFound(plcyNo);

        BaseMap out = new BaseMap();
        out.put("정책명", p.get("plcyNm"));
        out.put("신청방법", p.get("plcyAplyMthdCn"));
        out.put("제출서류", p.get("sbmsnDcmntCn"));
        out.put("심사방법", p.get("srngMthdCn"));
        out.put("신청기간구분코드", p.get("aplyPrdSeCd"));
        out.put("신청기간", p.get("aplyYmd"));
        out.put("신청URL", p.get("aplyUrlAddr"));
        out.put("참고URL", p.get("refUrlAddr1"));
        return out;
    }

    private BaseMap baseParam() {
        BaseMap param = new BaseMap();
        param.put("apiKeyNm", currentApiKey());
        param.put("rtnType", "json");
        return param;
    }

    private String currentApiKey() {
        try {
            HttpServletRequest request = ((ServletRequestAttributes)
                    RequestContextHolder.currentRequestAttributes()).getRequest();
            return request.getHeader("apiKeyNm");
        } catch (Exception e) {
            return null;
        }
    }

    /** 정책번호로 단건 상세 조회. */
    private BaseMap fetchOne(String plcyNo) {
        BaseMap param = baseParam();
        param.put("plcyNo", plcyNo);
        param.put("pageType", "2");
        List<BaseMap> list = fetchList(param);
        return list.isEmpty() ? null : list.get(0);
    }

    @SuppressWarnings("unchecked")
    private List<BaseMap> fetchList(BaseMap param) {
        BaseMap res = apiService.callGet(baseUrl, param);
        if (res == null) return List.of();

        Object resultObj = res.get("result");
        if (!(resultObj instanceof BaseMap)) return List.of();

        Object listObj = ((BaseMap) resultObj).get("youthPolicyList");
        if (!(listObj instanceof List)) return List.of();

        return (List<BaseMap>) listObj;
    }

    private BaseMap notFound(String plcyNo) {
        BaseMap out = new BaseMap();
        out.put("error", "해당 정책을 찾을 수 없습니다: " + plcyNo);
        return out;
    }

    private BaseMap toSummary(BaseMap p) {
        return new BaseMap()
                .set("정책번호", p.get("plcyNo"))
                .set("정책명", p.get("plcyNm"))
                .set("설명", p.get("plcyExplnCn"))
                .set("분류", p.get("lclsfNm"))
                .set("지원연령", ageRange(p))
                .set("운영기관", p.get("operInstCdNm"));
    }

    private BaseMap toDetail(BaseMap p) {
        return new BaseMap()
                .set("정책번호", p.get("plcyNo"))
                .set("정책명", p.get("plcyNm"))
                .set("설명", p.get("plcyExplnCn"))
                .set("지원내용", p.get("plcySprtCn"))
                .set("대분류", p.get("lclsfNm"))
                .set("중분류", p.get("mclsfNm"))
                .set("지원연령", ageRange(p))
                .set("결혼조건코드", p.get("mrgSttsCd"))
                .set("소득조건코드", p.get("earnCndSeCd"))
                .set("취업요건코드", p.get("jobCd"))
                .set("학력요건코드", p.get("schoolCd"))
                .set("전공요건코드", p.get("plcyMajorCd"))
                .set("특화요건코드", p.get("sbizCd"))
                .set("추가자격", p.get("addAplyQlfcCndCn"))
                .set("참여제한", p.get("ptcpPrpTrgtCn"))
                .set("신청방법", p.get("plcyAplyMthdCn"))
                .set("제출서류", p.get("sbmsnDcmntCn"))
                .set("신청URL", p.get("aplyUrlAddr"))
                .set("참고URL", p.get("refUrlAddr1"))
                .set("운영기관", p.get("operInstCdNm"));
    }

    private String ageRange(BaseMap p) {
        Object lmt = p.get("sprtTrgtAgeLmtYn");
        if (lmt != null && "N".equals(lmt.toString())) return "제한없음";
        return p.get("sprtTrgtMinAge") + "~" + p.get("sprtTrgtMaxAge") + "세";
    }

    private boolean ageMatches(BaseMap p, int age) {
        Object lmt = p.get("sprtTrgtAgeLmtYn");
        if (lmt != null && "N".equals(lmt.toString())) return true;   // 연령 제한 없음
        int min = parseIntSafe(p.get("sprtTrgtMinAge"), 0);
        int max = parseIntSafe(p.get("sprtTrgtMaxAge"), 999);
        return age >= min && age <= max;
    }

    private boolean marriageMatches(BaseMap p, String marriage) {
        if (marriage == null) return true;
        Object code = p.get("mrgSttsCd");
        if (code == null) return true;
        String c = code.toString().trim();
        if ("0055003".equals(c)) return true;             // 제한없음
        if ("0055001".equals(c)) return "기혼".equals(marriage);
        if ("0055002".equals(c)) return "미혼".equals(marriage);
        return true;
    }

    private int parseIntSafe(Object o, int def) {
        try {
            if (o == null || o.toString().isBlank()) return def;
            return Integer.parseInt(o.toString().trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
