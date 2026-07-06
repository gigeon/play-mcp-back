package play.mcp.back;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import play.mcp.back.common.BaseMap;
import play.mcp.back.domain.api.service.ApiService;
import play.mcp.back.domain.region.service.RegionCodeService;
import play.mcp.back.tools.YouthPoilyTool;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * matchYouthPolicy 의 나이·거주지(법정동코드 prefix) 매칭 로직을 외부 네트워크 없이 검증한다.
 * ApiService 를 목으로 대체하여 청년정책 목록과 법정동코드 응답을 고정값으로 주입한다.
 */
class MatchYouthPolicyUnitTest {

    private YouthPoilyTool tool;

    @BeforeEach
    void setUp() {
        ApiService apiService = mock(ApiService.class);

        // (1) 법정동 API: 사용자 거주지("서울특별시 은평구 응암동") → region_cd 1138010700
        when(apiService.callGetAsMap(anyString(), any(BaseMap.class)))
                .thenReturn(stanReginResp("1138010700"));

        // (2) 청년정책 API: 지역이 뒤섞인 정책 목록
        when(apiService.callGet(anyString(), any(BaseMap.class)))
                .thenReturn(youthResp(
                        policy("은평구 청년월세", "0054002", "11380", 19, 34),          // 매칭 O
                        policy("부산 청년월세", "0054002", "26110,26140", 19, 34),       // 지역 불일치 → X
                        policy("전국 청년정책", "0054001", "", 19, 39),                 // 중앙부처=전국 → O
                        policy("서울시 청년정책", "0054002", "11110,11380,11710", 19, 34),// 은평구 포함 → O
                        policy("은평구 중장년정책", "0054002", "11380", 40, 49)           // 나이 불일치 → X
                ));

        RegionCodeService regionCodeService = new RegionCodeService(apiService);
        ReflectionTestUtils.setField(regionCodeService, "baseUrl", "http://region");
        ReflectionTestUtils.setField(regionCodeService, "serviceKey", "sk");

        tool = new YouthPoilyTool(apiService, regionCodeService);
        ReflectionTestUtils.setField(tool, "baseUrl", "http://youth");
        ReflectionTestUtils.setField(tool, "youthApiKey", "k");
    }

    @Test
    void 나이와_거주지코드로_정책을_필터링한다() {
        BaseMap out = tool.matchYouthPolicy(28, "서울 은평구 응암동 125-11", null);

        System.out.println("[unit] resolvedRegionCodes = " + out.get("resolvedRegionCodes"));
        System.out.println("[unit] matchedCount        = " + out.get("matchedCount"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> policies = (List<Map<String, Object>>) out.get("policies");
        policies.forEach(p -> System.out.println("   매칭: " + p.get("plcyNm") + "  zipCd=" + p.get("zipCd")));

        // 은평구 / 전국 / 서울시(은평구 포함) 3건만 매칭, 부산·중장년 제외
        assertEquals(3, out.get("matchedCount"));
        List<String> names = policies.stream().map(p -> String.valueOf(p.get("plcyNm"))).toList();
        assertTrue(names.contains("은평구 청년월세"));
        assertTrue(names.contains("전국 청년정책"));
        assertTrue(names.contains("서울시 청년정책"));
        assertFalse(names.contains("부산 청년월세"), "다른 지역 정책은 제외되어야 한다");
        assertFalse(names.contains("은평구 중장년정책"), "나이 밖 정책은 제외되어야 한다");
    }

    @Test
    void 거주지_미입력시_나이만으로_필터링한다() {
        BaseMap out = tool.matchYouthPolicy(28, null, null);
        // region 이 없으면 지역 조건은 통과 → 나이(28)만으로 필터 → 중장년(40-49) 1건만 제외
        assertEquals(4, out.get("matchedCount"));
    }

    /* ───────────── 고정 응답 빌더 ───────────── */

    private BaseMap youthResp(Map<String, Object>... policies) {
        List<Map<String, Object>> list = new ArrayList<>(List.of(policies));
        Map<String, Object> result = new HashMap<>();
        result.put("youthPolicyList", list);
        BaseMap resp = new BaseMap();
        resp.put("result", result);
        return resp;
    }

    private Map<String, Object> policy(String name, String grpCd, String zipCd, int minAge, int maxAge) {
        Map<String, Object> m = new HashMap<>();
        m.put("plcyNm", name);
        m.put("pvsnInstGroupCd", grpCd);
        m.put("zipCd", zipCd);
        m.put("sprtTrgtMinAge", String.valueOf(minAge));
        m.put("sprtTrgtMaxAge", String.valueOf(maxAge));
        return m;
    }

    private BaseMap stanReginResp(String regionCd) {
        Map<String, Object> row = new HashMap<>();
        row.put("region_cd", regionCd);
        Map<String, Object> rowSection = new HashMap<>();
        rowSection.put("row", new ArrayList<>(List.of(row)));
        BaseMap resp = new BaseMap();
        resp.put("StanReginCd", new ArrayList<>(List.of(new HashMap<>(), rowSection)));
        return resp;
    }
}
