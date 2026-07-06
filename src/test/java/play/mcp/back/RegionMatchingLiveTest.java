package play.mcp.back;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import play.mcp.back.domain.region.service.RegionCodeService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 주소 → 법정동코드 변환 end-to-end 확인 (실제 행정안전부 법정동코드 API 호출, http).
 */
@SpringBootTest
class RegionMatchingLiveTest {

    @Autowired
    RegionCodeService regionCodeService;

    @Test
    void 주소_정규화는_번지제거_동유지_시도확장을_한다() {
        assertEquals("응암동", regionCodeService.normalizeAddress("응암동 125-11"));
        assertEquals("서울특별시 은평구 응암동", regionCodeService.normalizeAddress("서울 은평구 응암동 125-11"));
        assertEquals("역삼동", regionCodeService.normalizeAddress("역삼동 테헤란로 123"));
        assertEquals("부산광역시 해운대구 우동", regionCodeService.normalizeAddress("부산 해운대구 우동"));
    }

    @Test
    void 전체주소를_넣으면_법정동코드로_변환된다() {
        List<String> codes = regionCodeService.resolveRegionCodesByAddress("서울 은평구 응암동 125-11");
        System.out.println("[resolveByAddress] 서울 은평구 응암동 125-11 -> " + codes);
        assertFalse(codes.isEmpty(), "법정동코드가 조회되어야 한다");
        assertTrue(codes.stream().anyMatch(c -> c.startsWith("1138")),
                "은평구(11380) 코드가 포함되어야 한다: " + codes);
    }

    @Test
    void 동_이름만_넣어도_변환된다() {
        List<String> codes = regionCodeService.resolveRegionCodes("역삼동");
        System.out.println("[resolve] 역삼동 -> " + codes);
        assertTrue(codes.stream().anyMatch(c -> c.startsWith("11680")),
                "강남구(11680) 코드가 포함되어야 한다: " + codes);
    }
}
