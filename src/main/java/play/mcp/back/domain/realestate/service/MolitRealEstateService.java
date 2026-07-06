package play.mcp.back.domain.realestate.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import play.mcp.back.domain.realestate.model.Deal;

import java.net.URI;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 국토부(MOLIT) 실거래가 공식 API. 공공데이터포털 XML.
 *
 * <p>{@code housingType × dealType} 조합마다 데이터셋/엔드포인트가 다르다.
 * serviceKey 는 <b>인코딩 키</b>(이미 URL 인코딩됨)라 다시 인코딩하면 인증 실패 →
 * URL 에 raw 로 붙인다.</p>
 */
@Service
public class MolitRealEstateService implements RealEstateService {

    private static final Logger log = LoggerFactory.getLogger(MolitRealEstateService.class);

    /** LAWD_CD 없이는 조회 불가한 지역이라 결과 없음을 반환할 때 사용. */
    private static final List<Deal> EMPTY = List.of();

    /** 최근 몇 개월을 조회할지 (실거래는 신고 지연이 있어 여러 달을 훑는다). */
    private static final int MONTHS_BACK = 3;

    /** (주택유형, 거래유형) → API 경로. 거래유형은 전세/월세를 "전월세"로 합친다. */
    private static final Map<String, String> ENDPOINTS = Map.of(
            "아파트|매매", "/RTMSDataSvcAptTrade/getRTMSDataSvcAptTrade",
            "아파트|전월세", "/RTMSDataSvcAptRent/getRTMSDataSvcAptRent",
            "오피스텔|매매", "/RTMSDataSvcOffiTrade/getRTMSDataSvcOffiTrade",
            "오피스텔|전월세", "/RTMSDataSvcOffiRent/getRTMSDataSvcOffiRent",
            "빌라|매매", "/RTMSDataSvcRHTrade/getRTMSDataSvcRHTrade",
            "빌라|전월세", "/RTMSDataSvcRHRent/getRTMSDataSvcRHRent"
    );

    private final RestClient restClient = RestClient.create();
    private final XmlMapper xmlMapper = new XmlMapper();

    @Value("${realestate.baseUrl}")
    private String baseUrl;

    /** 공공데이터포털 인코딩 키(이미 URL 인코딩됨). */
    @Value("${realestate.serviceKey:}")
    private String serviceKey;

    @Override
    public List<Deal> findDeals(String region, String housingType, String dealType) {
        String lawdCd = LawdCode.resolve(region);
        if (lawdCd == null) {
            log.warn("MOLIT: region '{}' 에 해당하는 LAWD_CD 매핑이 없어 조회를 건너뜁니다.", region);
            return EMPTY;
        }
        if (serviceKey == null || serviceKey.isBlank()) {
            log.warn("MOLIT: REALESTATE_SERVICE_KEY 미설정. 빈 목록 반환.");
            return EMPTY;
        }

        String endpoint = ENDPOINTS.get(housingType + "|" + normalizeDealType(dealType));
        if (endpoint == null) {
            log.warn("MOLIT: 지원하지 않는 조합 housingType={}, dealType={}", housingType, dealType);
            return EMPTY;
        }

        List<Deal> result = new ArrayList<>();
        YearMonth ym = YearMonth.now();
        for (int i = 0; i < MONTHS_BACK; i++, ym = ym.minusMonths(1)) {
            result.addAll(query(endpoint, lawdCd, ym, housingType, dealType));
        }
        // 전월세 엔드포인트는 전세·월세가 함께 오므로 요청한 거래유형만 남긴다.
        // 전세=월세 0원, 월세=월세 1원 이상. (매매는 필터 없음)
        if ("전세".equals(dealType)) {
            result.removeIf(d -> d.monthlyRent() > 0);
        } else if ("월세".equals(dealType)) {
            result.removeIf(d -> d.monthlyRent() <= 0);
        }
        return result;
    }

    private static String normalizeDealType(String dealType) {
        return "매매".equals(dealType) ? "매매" : "전월세";
    }

    private List<Deal> query(String endpoint, String lawdCd, YearMonth ym,
                             String housingType, String dealType) {
        String dealYmd = "%04d%02d".formatted(ym.getYear(), ym.getMonthValue());
        // serviceKey 는 인코딩 키라 raw 로 붙인다(재인코딩 금지). 나머지는 ASCII 숫자라 인코딩 불필요.
        String url = baseUrl + endpoint
                + "?serviceKey=" + serviceKey
                + "&LAWD_CD=" + lawdCd
                + "&DEAL_YMD=" + dealYmd
                + "&pageNo=1&numOfRows=100";

        try {
            // 공공데이터포털 WAF 가 기본 User-Agent 요청을 400(Request Blocked)으로 막으므로 UA 를 명시한다.
            String xml = restClient.get().uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (play-mcp-back)")
                    .retrieve().body(String.class);
            if (xml == null || xml.isBlank()) {
                return List.of();
            }
            return parse(xml, lawdCd, housingType, dealType);
        } catch (Exception e) {
            log.warn("MOLIT 호출/파싱 실패 ({} {}): {}", endpoint, dealYmd, e.getMessage());
            return List.of();
        }
    }

    private List<Deal> parse(String xml, String lawdCd, String housingType, String dealType) throws Exception {
        JsonNode root = xmlMapper.readTree(xml);
        JsonNode items = root.path("body").path("items").path("item");
        if (items.isMissingNode()) {
            return List.of();
        }

        List<Deal> deals = new ArrayList<>();
        // item 은 단건이면 객체, 여러 건이면 배열로 온다.
        if (items.isArray()) {
            for (JsonNode item : items) {
                deals.add(toDeal(item, lawdCd, housingType, dealType));
            }
        } else {
            deals.add(toDeal(items, lawdCd, housingType, dealType));
        }
        return deals;
    }

    private Deal toDeal(JsonNode item, String lawdCd, String housingType, String dealType) {
        String sgg = text(item, "sggNm"); // 시군구명(예: 강남구, 서구)
        String umd = text(item, "umdNm"); // 읍면동명(예: 역삼동)
        String buildingName = firstNonBlank(
                text(item, "aptNm"), text(item, "offiNm"), text(item, "mhouseNm"),
                text(item, "houseNm"));
        int area = (int) Math.round(parseNum(text(item, "excluUseAr")));

        int year = (int) parseNum(text(item, "dealYear"));
        int month = (int) parseNum(text(item, "dealMonth"));
        String dealYmd = year > 0 ? "%04d%02d".formatted(year, month) : "";

        int deposit = 0, monthlyRent = 0, price = 0;
        if ("매매".equals(dealType)) {
            price = (int) parseNum(text(item, "dealAmount"));
        } else {
            deposit = (int) parseNum(text(item, "deposit"));
            monthlyRent = (int) parseNum(text(item, "monthlyRent"));
        }

        String address = ((sgg == null ? "" : sgg) + " " + (umd == null ? "" : umd)).trim();
        return new Deal(housingType, dealType, address, buildingName,
                deposit, monthlyRent, price, area, dealYmd, lawdCd);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null ? null : v.asText().trim();
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) return v;
        }
        return "";
    }

    /** "9,500", " 34,000 " 같은 콤마/공백 포함 숫자 문자열을 안전하게 파싱. */
    private static double parseNum(String s) {
        if (s == null || s.isBlank()) return 0;
        String cleaned = s.replaceAll("[^0-9.\\-]", "");
        if (cleaned.isBlank() || cleaned.equals("-")) return 0;
        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
