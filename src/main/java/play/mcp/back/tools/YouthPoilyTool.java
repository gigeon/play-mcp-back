package play.mcp.back.tools;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import play.mcp.back.common.BaseMap;
import play.mcp.back.common.McpTool;
import play.mcp.back.domain.api.service.ApiService;

@Service
@RequiredArgsConstructor
public class YouthPoilyTool implements McpTool {

    private final ApiService apiService;

    @Value("${api.baseUrl}")
    private String baseUrl;

    @Value("${api.key}")
    private String apiKey;

    @Tool(description = """
        청년 정책을 대분류, 중분류, 키워드, 지역 조건으로 검색한다.
        사용자가 특정 분야나 조건의 청년 지원 정책 목록을 찾을 때 사용한다.
        여러 조건을 동시에 걸 수 있으며, 조건이 없으면 전체에서 검색한다.
        상세 정보가 필요하면 결과의 plcyNo로 getPolicyDetail을 사용한다.
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
        BaseMap param = new BaseMap();
        param.put("apiKeyNm", apiKey);
        param.put("rtnType", "json");
        param.put("pageNum", "1");
        param.put("pageSize", "10");
        if (lclsfNm != null) param.put("lclsfNm", lclsfNm);
        if (mclsfNm != null) param.put("mclsfNm", mclsfNm);
        if (keyword != null) param.put("plcyKywdNm", keyword);
        if (zipCd != null)   param.put("zipCd", zipCd);

        return apiService.callGet(baseUrl, param);
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
                관심 분야 대분류명 (선택).
                일자리 / 주거 / 교육 / 복지문화 / 참여권리
                """, required = false)
            String lclsfNm,
            @ToolParam(description = "관심 키워드 (선택)", required = false)
            String keyword
    ) {
        BaseMap param = new BaseMap();
        param.put("apiKeyNm", apiKey);
        param.put("rtnType", "json");
        param.put("pageNum", "1");
        param.put("pageSize", "50");
        if (lclsfNm != null) param.put("lclsfNm", lclsfNm);
        if (keyword != null) param.put("plcyKywdNm", keyword);

        return apiService.callGet(baseUrl, param);
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
        BaseMap param = new BaseMap();
        param.put("apiKeyNm", apiKey);
        param.put("rtnType", "json");
        param.put("plcyNo", plcyNo);
        param.put("pageType", "2");

        return apiService.callGet(baseUrl, param);
    }

    @Tool(description = """
        여러 정책을 비교할 때 사용한다. 정책번호마다 상세를 조회해 반환하므로,
        AI가 응답들을 나란히 놓고 지원내용, 연령, 소득조건 등 차이를 정리한다.
        한 번에 여러 번 호출해도 되며, 정책번호 하나를 받아 상세를 반환한다.
        """)
    public BaseMap comparePolicy(
            @ToolParam(description = "비교할 정책 번호 하나") String plcyNo
    ) {
        BaseMap param = new BaseMap();
        param.put("apiKeyNm", apiKey);
        param.put("rtnType", "json");
        param.put("plcyNo", plcyNo);
        param.put("pageType", "2");

        return apiService.callGet(baseUrl, param);
    }

    @Tool(description = """
        정책의 신청 방법을 안내할 때 사용한다. 정책번호로 상세를 조회하며,
        응답의 신청방법(plcyAplyMthdCn), 제출서류(sbmsnDcmntCn),
        신청URL(aplyUrlAddr), 심사방법(srngMthdCn)을 참고해 안내한다.
        """)
    public BaseMap getApplyGuide(
            @ToolParam(description = "정책 번호") String plcyNo
    ) {
        BaseMap param = new BaseMap();
        param.put("apiKeyNm", apiKey);
        param.put("rtnType", "json");
        param.put("plcyNo", plcyNo);
        param.put("pageType", "2");

        return apiService.callGet(baseUrl, param);
    }
}
