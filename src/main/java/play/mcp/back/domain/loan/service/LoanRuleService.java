package play.mcp.back.domain.loan.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 전월세보증금대출 자격/한도 판정.
 *
 * <p>⚠️ 아래 {@link #CATALOG} 의 자격/한도 수치는 <b>주택도시기금 공고 근사치</b>다.
 * 실제 서비스 전에 최신 기준으로 반드시 검증한 뒤 표를 갱신할 것.</p>
 *
 * <p>금액 단위는 모두 <b>만원</b>.</p>
 */
@Service
public class LoanRuleService {

    /** 대출 상품 한 건. maxLimit 는 최대 대출 한도(만원). */
    public record LoanProduct(String name, int maxLimit, String note) {
    }

    /** 판정 결과. products = 자격 통과 상품들, maxLimit = 그중 최대 한도(만원). */
    public record LoanResult(List<LoanProduct> products, int maxLimit) {
    }

    /**
     * 대출 상품 카탈로그(근사치).
     * 조건: 최대 나이, 미혼 여부 무관(여기서는 나이/연소득/보증금 상한만 사용),
     *       최대 연소득(만원), 최대 보증금(만원), 최대 한도(만원).
     */
    private record Rule(String name, int maxAge, int maxIncome, int maxDeposit,
                        int maxLimit, boolean marriedAllowed, String note) {
    }

    private static final List<Rule> CATALOG = List.of(
            // 중소기업취업청년 전월세보증금대출 (중기청)
            new Rule("중소기업취업청년 전월세보증금대출", 34, 3500, 20000, 10000, false,
                    "중소기업 재직 청년, 임차보증금 2억 이하, 연 1.5% 내외"),
            // 청년전용 버팀목 전세자금대출
            new Rule("청년전용 버팀목 전세자금대출", 34, 5000, 30000, 20000, false,
                    "만 19~34세, 무주택 세대주, 보증금 3억 이하"),
            // 일반 버팀목 전세자금대출 (연령 제한 완화, 혼인 허용)
            new Rule("버팀목 전세자금대출", 100, 5000, 30000, 12000, true,
                    "무주택 세대주, 부부합산 연소득 5천 이하")
    );

    /**
     * 자격 판정.
     *
     * @param age     나이(만)
     * @param married 혼인 여부
     * @param income  연소득(만원)
     * @param deposit 희망 보증금(만원)
     * @param region  지역(현재 판정에는 미사용, 향후 지역별 우대 확장 지점)
     * @return 자격 통과 상품 목록 + 최대 한도(만원)
     */
    public LoanResult judge(int age, boolean married, int income, int deposit, String region) {
        List<LoanProduct> passed = new ArrayList<>();
        int maxLimit = 0;

        for (Rule r : CATALOG) {
            if (age > r.maxAge()) continue;
            if (married && !r.marriedAllowed()) continue;
            if (income > r.maxIncome()) continue;
            if (deposit > r.maxDeposit()) continue;

            passed.add(new LoanProduct(r.name(), r.maxLimit(), r.note()));
            maxLimit = Math.max(maxLimit, r.maxLimit());
        }

        return new LoanResult(passed, maxLimit);
    }
}
