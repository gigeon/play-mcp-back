package play.mcp.back.domain.realestate.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import play.mcp.back.domain.realestate.model.Deal;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 번들된 {@code resources/sample-deals.json} 기반 실거래 조회. 키가 없어도 개발/시연이 가능한
 * 기본 소스다.
 */
@Service
public class SampleRealEstateService implements RealEstateService {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private List<Deal> deals = Collections.emptyList();

    @PostConstruct
    void load() throws Exception {
        try (InputStream in = new ClassPathResource("sample-deals.json").getInputStream()) {
            this.deals = objectMapper.readValue(in, new com.fasterxml.jackson.core.type.TypeReference<>() {});
        }
    }

    @Override
    public List<Deal> findDeals(String region, String housingType, String dealType) {
        String lawdCd = LawdCode.resolve(region);
        return deals.stream()
                .filter(d -> housingType == null || housingType.isBlank() || housingType.equals(d.type()))
                .filter(d -> dealType == null || dealType.isBlank() || dealType.equals(d.dealType()))
                // 지역이 특정 구로 좁혀지면 해당 코드만, "서울"처럼 넓으면 주소에 지역 포함 여부로 필터
                .filter(d -> matchesRegion(d, region, lawdCd))
                .toList();
    }

    private boolean matchesRegion(Deal d, String region, String lawdCd) {
        if (region == null || region.isBlank()) return true;
        if (lawdCd != null && lawdCd.equals(d.lawdCd())) return true;
        String r = region.toLowerCase(Locale.ROOT);
        String addr = d.address() == null ? "" : d.address().toLowerCase(Locale.ROOT);
        // "서울" 만 준 경우 등 넓은 조건은 주소 부분매칭으로 허용
        return addr.contains(r) || (region.contains("서울") && addr.contains("서울"));
    }
}
