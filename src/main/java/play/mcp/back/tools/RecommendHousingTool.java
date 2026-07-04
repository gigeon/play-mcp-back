package play.mcp.back.tools;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;
import play.mcp.back.domain.kakao.model.NearbyPlace;
import play.mcp.back.domain.kakao.service.KakaoLocalService;
import play.mcp.back.domain.loan.service.LoanRuleService;
import play.mcp.back.domain.loan.service.LoanRuleService.LoanResult;
import play.mcp.back.domain.realestate.model.Deal;
import play.mcp.back.domain.realestate.service.RealEstateService;

import java.util.ArrayList;
import java.util.List;

/**
 * 청년 주거 추천 도구. 대출한도를 판정하고 그 한도 안에서 거래된 실거래 매물을 추천하며,
 * 각 매물 인근 지하철역/학교 정보를 붙여준다. 금액 단위는 모두 <b>만원</b>.
 */
@Service
@RequiredArgsConstructor
public class RecommendHousingTool {

    /** 반경(m) 및 반환 개수 상수. */
    private static final int RADIUS_M = 1000;
    private static final int NEARBY_LIMIT = 3;
    private static final int MAX_DEALS = 10;

    private final LoanRuleService loanRuleService;
    private final RealEstateService realEstateService;
    private final KakaoLocalService kakaoLocalService;

    /** 추천 매물 한 건 + 인근 지하철/학교. */
    public record RecommendedDeal(Deal deal, List<NearbyPlace> subways, List<NearbyPlace> schools) {
    }

    /** 도구 응답. loan = 대출 판정, deals = 한도 내 추천 매물. */
    public record HousingRecommendation(LoanResult loan, List<RecommendedDeal> deals) {
    }

    @Tool(description = """
        청년 주거 추천.
        전월세보증금대출 자격/한도를 판정하고, 그 한도 안에서 거래된 실거래 매물을 추천하며
        각 매물 인근 지하철역/학교 정보를 붙여준다.
        주의: 실거래가는 '과거 실제 거래'이지 현재 매물이 아니다.
        추천은 '최근 내 대출한도 안에 거래된 곳'을 의미한다. 금액 단위는 모두 만원.

        Args:
        region: 지역 (예: "서울", "서울 강남구")
        housingType: 아파트/오피스텔/빌라
        dealType: 전세/월세/매매
        age: 나이(만)
        married: 혼인 여부
        income: 연소득(만원)
    """)
    public HousingRecommendation recommendHousing(
            @ToolParam(description = "지역 (예: 서울, 서울 강남구)") String region,
            @ToolParam(description = "주택유형: 아파트/오피스텔/빌라") String housingType,
            @ToolParam(description = "거래유형: 전세/월세/매매") String dealType,
            @ToolParam(description = "나이(만)") int age,
            @ToolParam(description = "혼인 여부") boolean married,
            @ToolParam(description = "연소득(만원)") int income
    ) {
        // 1) 대출한도 판정. 보증금 상한은 아직 모르므로 소득 기준 판정 후 한도만 사용.
        LoanResult loan = loanRuleService.judge(age, married, income, 0, region);
        int limit = loan.maxLimit();

        // 2) 실거래 조회 → 3) 한도 내 매물 필터
        List<Deal> deals = realEstateService.findDeals(region, housingType, dealType);
        List<Deal> affordable = deals.stream()
                .filter(d -> limit <= 0 || d.withinLimit(limit))
                .limit(MAX_DEALS)
                .toList();

        // 4) 각 매물 지오코딩 → 인근 지하철/학교 부착 (카카오 키 없으면 빈 목록)
        List<RecommendedDeal> recommended = new ArrayList<>();
        for (Deal d : affordable) {
            List<NearbyPlace> subways = List.of();
            List<NearbyPlace> schools = List.of();
            double[] xy = kakaoLocalService.geocode(d.address() + " " + d.buildingName());
            if (xy != null) {
                subways = kakaoLocalService.nearby(xy[0], xy[1], KakaoLocalService.SUBWAY, RADIUS_M, NEARBY_LIMIT);
                schools = kakaoLocalService.nearby(xy[0], xy[1], KakaoLocalService.SCHOOL, RADIUS_M, NEARBY_LIMIT);
            }
            recommended.add(new RecommendedDeal(d, subways, schools));
        }

        return new HousingRecommendation(loan, recommended);
    }
}
