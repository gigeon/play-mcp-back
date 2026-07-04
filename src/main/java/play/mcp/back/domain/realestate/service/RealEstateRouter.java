package play.mcp.back.domain.realestate.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import play.mcp.back.domain.realestate.model.Deal;

import java.util.List;

/**
 * {@code realestate.source} 값(sample|molit)에 따라 실거래 구현체를 고르는 라우터.
 * 미지정/알 수 없는 값은 {@code sample} 로 폴백한다. {@link Primary} 라서
 * {@link RealEstateService} 로 주입받으면 이 라우터가 온다.
 */
@Service
@Primary
@RequiredArgsConstructor
public class RealEstateRouter implements RealEstateService {

    private static final Logger log = LoggerFactory.getLogger(RealEstateRouter.class);

    private final SampleRealEstateService sample;
    private final MolitRealEstateService molit;

    @Value("${realestate.source:sample}")
    private String source;

    @Override
    public List<Deal> findDeals(String region, String housingType, String dealType) {
        RealEstateService delegate = "molit".equalsIgnoreCase(source) ? molit : sample;
        if (!"molit".equalsIgnoreCase(source) && !"sample".equalsIgnoreCase(source)) {
            log.warn("알 수 없는 realestate.source='{}', sample 로 폴백", source);
        }
        return delegate.findDeals(region, housingType, dealType);
    }
}
