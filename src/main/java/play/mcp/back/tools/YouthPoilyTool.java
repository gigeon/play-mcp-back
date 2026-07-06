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
import java.util.Map;

/**
 * 청년정책 조회/매칭 MCP 툴.
 * 온통청년 v2 API(/go/ythip/getPlcy)를 감싼다.
 *
 * 컨벤션(팀원 스캐폴딩)을 그대로 따름:
 *  - 인증키는 MCP 요청 헤더 "apiKeyNm" 에서 읽어, youthcenter 에는 쿼리 파라미터로 전달
 *  - HTTP 호출은 ApiService.callGet(url, BaseMap), 응답은 BaseMap(JSON)
 *  - @Tool 메서드는 같은 클래스에 추가만 하면 McpServerConfig 수정 없이 등록됨
 *
 * ── 실제 v2 응답으로 검증 완료(2026-07-02, curl 확인)
 *  1) 키워드 검색 파라미터: plcyNm (정책명 부분매칭). 예) plcyNm=월세 → 53건, plcyNm=취업 → 103건.
 *     ※ plcyKywdNm 은 등록된 '키워드 태그' 정확매칭이라 임의 입력에 취약(월세→0건)하여 사용하지 않음.
 *  2) 응답 목록 경로: result.youthPolicyList (검색·상세 공통)
 *  3) 연령 필드: sprtTrgtMinAge / sprtTrgtMaxAge (문자열), 연령무관 플래그 sprtTrgtAgeLmtYn
 *  4) 상세 조회 파라미터: plcyNo (단건이 result.youthPolicyList 에 담겨 옴)
 */
@Service
@RequiredArgsConstructor
public class YouthPoilyTool {

    private final ApiService apiService;

    @Value("${api.baseUrl}")
    private String baseUrl;

    @Value("${api.youth-key}")
    private String youthApiKey;

    /* ───────────────────────── 공통 유틸 ───────────────────────── */

    /** MCP 요청 헤더에서 API 인증키를 꺼낸다. */
    private String apiKey() {
        // 헤더가 있으면 헤더 우선, 없으면 설정값 사용 (개발 편의)
        try {
            HttpServletRequest request = ((ServletRequestAttributes)
                    RequestContextHolder.currentRequestAttributes()).getRequest();
            String header = request.getHeader("apiKeyNm");
            if (header != null && !header.isBlank()) return header;
        } catch (IllegalStateException ignored) {
            // 요청 컨텍스트 없을 때 무시하고 설정값으로
        }
        return youthApiKey;
    }

    /** 공통 요청 파라미터(검증된 최소 집합). */
    private BaseMap baseParam(int pageSize) {
        BaseMap p = new BaseMap();
        p.put("apiKeyNm", apiKey());
        p.put("pageSize", pageSize);
        return p;
    }

    private BaseMap callGetPlcy(BaseMap param) {
        return apiService.callGet(baseUrl + "/getPlcy", param);
    }

    /* ───────────────────────── 도구 1: 검색 ───────────────────────── */

    @Tool(description = """
        키워드로 청년정책을 검색한다.

        Args:
          keyword: 검색어 (예: 월세, 취업, 자격증, 창업) - 정책명에 대해 부분매칭한다
          lclsfNm: (선택) 정책대분류 필터 - 일자리 / 주거 / 교육 / 복지문화 / 참여권리
        """)
    public BaseMap searchYouthPolicy(
            @ToolParam(description = "검색 키워드 (예: 월세, 취업, 자격증) - 정책명 부분매칭") String keyword,
            @ToolParam(required = false, description = "정책대분류: 일자리/주거/교육/복지문화/참여권리")
            String lclsfNm
    ) {
        BaseMap p = baseParam(10);
        if (keyword != null && !keyword.isBlank()) {
            p.put("plcyNm", keyword);   // 정책명 부분매칭 (v2 확인 완료)
        }
        if (lclsfNm != null && !lclsfNm.isBlank()) {
            p.put("lclsfNm", lclsfNm);
        }
        return callGetPlcy(p);
    }

    /* ─────────────────────── 도구 2: 자격 매칭 ─────────────────────── */

    @Tool(description = """
        나이·거주지 조건으로 '신청 가능성이 있는' 청년정책만 골라준다.
        전국(중앙부처) 정책과 거주지 지자체 정책을 합쳐 나이로 필터링한다.
        지자체 정책은 거주기간 등 추가 요건이 있을 수 있어 결과는 '후보'로 안내한다.

        Args:
          age: 만 나이
          region: (선택) 주민등록상 거주지 (예: 서울, 경기, 부산)
          lclsfNm: (선택) 정책대분류로 범위 축소
        """)
    public BaseMap matchYouthPolicy(
            @ToolParam(description = "만 나이") int age,
            @ToolParam(required = false, description = "거주지 (예: 서울, 경기)") String region,
            @ToolParam(required = false, description = "정책대분류: 일자리/주거/교육/복지문화/참여권리")
            String lclsfNm
    ) {
        BaseMap p = baseParam(50);   // 넓게 받아서 조건은 서버에서 필터링
        if (lclsfNm != null && !lclsfNm.isBlank()) {
            p.put("lclsfNm", lclsfNm);
        }
        BaseMap resp = callGetPlcy(p);

        List<Map<String, Object>> matched = new ArrayList<>();
        for (Map<String, Object> it : extractList(resp)) {
            if (ageMatches(it, age) && regionMatches(it, region)) {
                matched.add(it);
            }
        }

        BaseMap out = new BaseMap();
        out.put("condition", "만 " + age + "세"
                + (region != null && !region.isBlank() ? " / " + region + " 거주" : ""));
        out.put("matchedCount", matched.size());
        out.put("policies", matched);
        out.put("notice", "지자체 정책은 거주기간 등 추가 요건이 있을 수 있습니다. 실제 신청 전 상세 요건을 확인하세요.");
        return out;
    }

    /* ─────────────────────── 도구 3: 상세 조회 ─────────────────────── */

    @Tool(description = """
        정책번호(plcyNo)로 청년정책 상세 정보를 조회한다.

        Args:
          plcyNo: 정책 고유번호 (검색/매칭 결과의 plcyNo)
        """)
    public BaseMap getYouthPolicyDetail(
            @ToolParam(description = "정책 고유번호 plcyNo") String plcyNo
    ) {
        BaseMap p = baseParam(5);
        p.put("plcyNo", plcyNo);   // 단건이 result.youthPolicyList 로 옴 (v2 확인 완료)
        return callGetPlcy(p);
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

    /** 나이 조건 매칭. 연령 정보가 없으면 통과시킨다. */
    private boolean ageMatches(Map<String, Object> it, int age) {
        Integer lo = parseInt(it.get("sprtTrgtMinAge"));
        Integer hi = parseInt(it.get("sprtTrgtMaxAge"));
        if (lo == null && hi == null) return true;                 // 연령 정보 없음 → 후보 포함
        if (lo != null && age < lo) return false;
        if (hi != null && hi > 0 && age > hi) return false;
        return true;
    }

    /**
     * 거주지 조건 매칭.
     * - 제공기관 그룹이 중앙부처(0054001)면 전국 대상 → 무조건 포함
     * - 지자체(0054002)면 등록/운영/주관 기관명에 거주지명이 포함되는지로 후보 판정
     *   (zipCd 는 법정동 코드라 지역명 텍스트 매칭에는 쓰지 않음)
     */
    private boolean regionMatches(Map<String, Object> it, String region) {
        if (region == null || region.isBlank()) return true;
        if ("0054001".equals(str(it.get("pvsnInstGroupCd")))) return true;  // 중앙부처=전국
        for (String key : new String[]{"rgtrInstCdNm", "operInstCdNm", "sprvsnInstCdNm"}) {
            String v = str(it.get(key));
            if (v != null && v.contains(region)) return true;
        }
        return false;
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
}
