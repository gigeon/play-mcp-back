package play.mcp.back.tools;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;
import play.mcp.back.common.McpTool;
import play.mcp.back.domain.kakao.model.NearbyPlace;
import play.mcp.back.domain.kakao.service.KakaoLocalService;
import play.mcp.back.domain.loan.service.LoanRuleService;
import play.mcp.back.domain.loan.service.LoanRuleService.LoanResult;
import play.mcp.back.domain.realestate.model.Deal;
import play.mcp.back.domain.realestate.service.LawdCode;
import play.mcp.back.domain.realestate.service.RealEstateService;
import play.mcp.back.domain.region.service.RegionCorrector;

import java.util.ArrayList;
import java.util.List;

/**
 * 청년 주거 추천 도구. 대출한도를 판정하고 그 한도 안에서 거래된 실거래 매물을 추천하며,
 * 각 매물 인근 지하철역/학교 정보를 붙여준다. 금액 단위는 모두 <b>만원</b>.
 */
@Service
@RequiredArgsConstructor
public class RecommendHousingTool implements McpTool {

    /** 반경(m) 및 반환 개수 상수. 지하철은 역이 드문 신도시(청라 등) 대응으로 더 넓게 잡는다. */
    private static final int SUBWAY_RADIUS_M = 3000;
    private static final int SCHOOL_RADIUS_M = 1500;
    private static final int NEARBY_LIMIT = 3;
    private static final int MAX_DEALS = 5;

    /** 유효한 입력 값. */
    private static final List<String> HOUSING_TYPES = List.of("아파트", "오피스텔", "빌라");
    private static final List<String> DEAL_TYPES = List.of("전세", "월세", "매매");

    private final LoanRuleService loanRuleService;
    private final RealEstateService realEstateService;
    private final KakaoLocalService kakaoLocalService;
    private final RegionCorrector regionCorrector;

    /** 추천 매물 한 건 + 인근 지하철/학교. */
    public record RecommendedDeal(Deal deal, List<NearbyPlace> subways, List<NearbyPlace> schools) {
    }

    /** 도구 응답. loan = 대출 판정, deals = 한도 내 추천 매물, notice = 결과가 없을 때 안내(없으면 null). */
    public record HousingRecommendation(LoanResult loan, List<RecommendedDeal> deals, String notice) {
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
        married: 혼인 여부 (기혼=true, 미혼=false)
        income: 연소득(만원)
    """)
    public HousingRecommendation recommendHousing(
            @ToolParam(description = "지역 (예: 서울, 서울 강남구)") String region,
            @ToolParam(description = "주택유형: 아파트/오피스텔/빌라") String housingType,
            @ToolParam(description = "거래유형: 전세/월세/매매") String dealType,
            @ToolParam(description = "나이(만)") int age,
            @ToolParam(description = "혼인 여부: 기혼이면 true, 미혼이면 false") boolean married,
            @ToolParam(description = "연소득(만원)") int income
    ) {
        // 0-1) 입력값 검증. 잘못된 값이면 조회 없이 안내만 반환.
        String error = validate(region, housingType, dealType, age, income);
        if (error != null) {
            return new HousingRecommendation(null, List.of(), error);
        }

        // 0-2) 지역명 오타 보정 (법정동 API 시군구 목록과 유사도 매칭, 예: "달서그"→"달서구"). 보정 시 notice.
        String fixedRegion = regionCorrector.correct(region);
        boolean typoFixed = !fixedRegion.equals(region.trim());

        // 1) 대출한도 판정. 보증금 상한은 아직 모르므로 소득 기준 판정 후 한도만 사용.
        LoanResult loan = loanRuleService.judge(age, married, income, 0, fixedRegion);
        int limit = loan.maxLimit();

        // 2) 실거래 조회 → 3) 한도 내 매물 필터 → 4) 인근 지하철/학교 부착
        List<Deal> deals = realEstateService.findDeals(fixedRegion, housingType, dealType);
        List<Deal> affordable = affordableDeals(deals, limit, LawdCode.extractDong(fixedRegion));
        List<RecommendedDeal> recommended = withNearby(affordable);

        String notice = joinNotices(
                typoFixed ? "입력하신 지역을 '" + fixedRegion + "'로 인식했습니다." : null,
                isSidoOnly(fixedRegion) ? "지역이 시/도 단위로 넓어 대표 지역만 조회했습니다. 정확한 결과를 위해 '구'까지 입력하세요(예: 서울 강남구)." : null,
                buildNotice(recommended, deals, limit));
        return new HousingRecommendation(loan, recommended, notice);
    }

    /**
     * 한도 내 매물 필터. 대출 가능 상품이 없으면(limit&lt;=0) 빈 목록,
     * 있으면 (요청 동으로 좁히고) 보증금이 한도 이하인 매물을 저렴한 순 상위 N건.
     */
    private List<Deal> affordableDeals(List<Deal> deals, int limit, String dong) {
        if (limit <= 0) return List.of();
        return deals.stream()
                .filter(d -> matchesDong(d, dong))
                .filter(d -> d.withinLimit(limit))
                .sorted(java.util.Comparator.comparingInt(RecommendHousingTool::cost)) // 저렴한 순 = 자기부담 적은 순
                .limit(MAX_DEALS)
                .toList();
    }

    /** 각 매물을 지오코딩해 인근 지하철/학교를 붙인다. 카카오 키 없으면 빈 목록으로 채워진다. */
    private List<RecommendedDeal> withNearby(List<Deal> deals) {
        List<RecommendedDeal> out = new ArrayList<>();
        for (Deal d : deals) {
            List<NearbyPlace> subways = List.of();
            List<NearbyPlace> schools = List.of();
            double[] xy = kakaoLocalService.geocode(d.address() + " " + d.buildingName());
            if (xy != null) {
                subways = kakaoLocalService.nearby(xy[0], xy[1], KakaoLocalService.SUBWAY, SUBWAY_RADIUS_M, NEARBY_LIMIT);
                schools = kakaoLocalService.nearby(xy[0], xy[1], KakaoLocalService.SCHOOL, SCHOOL_RADIUS_M, NEARBY_LIMIT);
            }
            out.add(new RecommendedDeal(d, subways, schools));
        }
        return out;
    }

    /** 시/도만 입력됐는지(구·군·시 없이). 넓은 조회라 대표 지역만 나옴을 안내하는 데 쓴다. */
    private static boolean isSidoOnly(String region) {
        if (region == null) return false;
        return !region.contains("구") && !region.contains("군") && LawdCode.extractDong(region) == null;
    }

    /** 입력값 검증. 문제가 있으면 안내 문구, 없으면 null. */
    private static String validate(String region, String housingType, String dealType, int age, long income) {
        List<String> errors = new ArrayList<>();
        if (region == null || region.isBlank()) {
            errors.add("지역(region)을 입력하세요.");
        }
        if (!HOUSING_TYPES.contains(housingType)) {
            errors.add("주택유형은 아파트/오피스텔/빌라 중 하나여야 합니다.");
        }
        if (!DEAL_TYPES.contains(dealType)) {
            errors.add("거래유형은 전세/월세/매매 중 하나여야 합니다.");
        }
        if (age < 19 || age > 120) {
            errors.add("나이는 19~120 사이여야 합니다.");
        }
        if (income < 0) {
            errors.add("연소득은 0 이상이어야 합니다.");
        }
        return errors.isEmpty() ? null : "입력값 오류: " + String.join(" ", errors);
    }

    /** null 이 아닌 안내들을 공백으로 이어 붙인다. 모두 null 이면 null. */
    private static String joinNotices(String... notices) {
        StringBuilder sb = new StringBuilder();
        for (String n : notices) {
            if (n != null && !n.isBlank()) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(n);
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** 추천 결과가 비었을 때 이유를 안내한다. 결과가 있으면 null. */
    private String buildNotice(List<RecommendedDeal> recommended, List<Deal> deals, int limit) {
        if (!recommended.isEmpty()) {
            return null;
        }
        if (limit <= 0) {
            return "나이·소득·혼인 조건으로 받을 수 있는 전월세보증금대출 상품이 없어 추천을 제공할 수 없습니다.";
        }
        if (deals.isEmpty()) {
            return "해당 지역·주택유형·거래구분의 최근 실거래 데이터가 없습니다. 지역이나 조건을 넓혀 다시 시도해 보세요.";
        }
        return "대출한도(" + limit + "만원) 내에서 거래된 매물이 없습니다. 지역/유형을 넓히거나 월세를 고려해 보세요.";
    }

    /** 정렬 기준 비용(만원): 매매는 매매가, 전월세는 보증금. */
    private static int cost(Deal d) {
        return "매매".equals(d.dealType()) ? d.price() : d.deposit();
    }

    /** 요청 동이 없으면 통과, 있으면 매물 주소가 그 동(끝의 '동' 제거 후 부분일치)인지 확인. */
    private static boolean matchesDong(Deal d, String dong) {
        if (dong == null || dong.isBlank()) return true;
        String key = dong.endsWith("동") ? dong.substring(0, dong.length() - 1) : dong;
        String addr = d.address() == null ? "" : d.address();
        return addr.contains(key);
    }
}
