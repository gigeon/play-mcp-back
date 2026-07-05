package play.mcp.back.domain.kakao.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import play.mcp.back.domain.kakao.model.NearbyPlace;

import java.util.List;

/**
 * 카카오 로컬 API — 주소 지오코딩 + 반경 내 카테고리 검색(지하철역 SW8 / 학교 SC4).
 *
 * <p>인증은 <b>카카오 어드민 키</b>({@code Authorization: KakaoAK {ADMIN_KEY}}).
 * 키가 없으면 모든 조회가 빈 목록을 반환한다(graceful degrade).</p>
 */
@Service
public class KakaoLocalService {

    private static final Logger log = LoggerFactory.getLogger(KakaoLocalService.class);
    private static final String BASE = "https://dapi.kakao.com";

    /** 카테고리 그룹 코드. */
    public static final String SUBWAY = "SW8";
    public static final String SCHOOL = "SC4";

    private final RestClient restClient = RestClient.create();

    @Value("${kakao.adminKey:}")
    private String adminKey;

    private boolean disabled() {
        return adminKey == null || adminKey.isBlank();
    }

    /**
     * 질의 → [경도(x), 위도(y)]. 실패/키없음 시 {@code null}.
     *
     * <p>매물명(건물명)이 섞인 질의는 주소검색(address.json)으로 못 잡히므로
     * <b>키워드검색(keyword.json)을 먼저</b> 시도해 건물 좌표를 정확히 찾고,
     * 없으면 주소검색으로 폴백한다(동 단위 근사 좌표).</p>
     */
    public double[] geocode(String query) {
        if (disabled() || query == null || query.isBlank()) return null;
        double[] byKeyword = search("/v2/local/search/keyword.json", query);
        return byKeyword != null ? byKeyword : search("/v2/local/search/address.json", query);
    }

    /** 카카오 검색 API 의 documents[0] 좌표를 반환. 없으면 null. */
    private double[] search(String path, String query) {
        try {
            // 한글 질의는 직접 1회만 URL 인코딩한다(UriComponentsBuilder.encode() 는 이중 인코딩 위험).
            String q = java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8);
            String uri = BASE + path + "?query=" + q;
            JsonNode root = get(uri);
            JsonNode docs = root == null ? null : root.path("documents");
            if (docs == null || !docs.isArray() || docs.isEmpty()) return null;
            JsonNode first = docs.get(0);
            double x = Double.parseDouble(first.path("x").asText("0")); // 경도
            double y = Double.parseDouble(first.path("y").asText("0")); // 위도
            if (x == 0 && y == 0) return null;
            return new double[]{x, y};
        } catch (Exception e) {
            log.warn("카카오 좌표검색 실패 ({} · {}): {}", path, query, e.getMessage());
            return null;
        }
    }

    /**
     * 좌표 반경 내 카테고리 검색.
     *
     * @param categoryCode {@link #SUBWAY} 또는 {@link #SCHOOL}
     * @param radiusM      반경(m)
     * @param limit        최대 반환 개수(거리순)
     */
    public List<NearbyPlace> nearby(double lng, double lat, String categoryCode, int radiusM, int limit) {
        if (disabled()) return List.of();
        try {
            String uri = UriComponentsBuilder.fromUriString(BASE + "/v2/local/search/category.json")
                    .queryParam("category_group_code", categoryCode)
                    .queryParam("x", lng)
                    .queryParam("y", lat)
                    .queryParam("radius", radiusM)
                    .queryParam("sort", "distance")
                    .build().encode().toUriString();
            JsonNode root = get(uri);
            JsonNode docs = root == null ? null : root.path("documents");
            if (docs == null || !docs.isArray()) return List.of();

            List<NearbyPlace> out = new java.util.ArrayList<>();
            for (JsonNode d : docs) {
                if (out.size() >= limit) break;
                out.add(new NearbyPlace(
                        d.path("place_name").asText(""),
                        d.path("category_group_name").asText(""),
                        (int) Double.parseDouble(d.path("distance").asText("0"))));
            }
            return out;
        } catch (Exception e) {
            log.warn("카카오 카테고리 검색 실패 ({}): {}", categoryCode, e.getMessage());
            return List.of();
        }
    }

    private JsonNode get(String uri) {
        // 이미 인코딩된 문자열이므로 URI 로 넘겨 RestClient 의 재인코딩(% → %25)을 막는다.
        return restClient.get()
                .uri(java.net.URI.create(uri))
                .header(HttpHeaders.AUTHORIZATION, "KakaoAK " + adminKey)
                .retrieve()
                .body(JsonNode.class);
    }
}
