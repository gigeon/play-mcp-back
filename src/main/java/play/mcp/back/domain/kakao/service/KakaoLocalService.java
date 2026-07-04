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

    /** 주소 → [경도(x), 위도(y)]. 실패/키없음 시 {@code null}. */
    public double[] geocode(String address) {
        if (disabled() || address == null || address.isBlank()) return null;
        try {
            String uri = UriComponentsBuilder.fromUriString(BASE + "/v2/local/search/address.json")
                    .queryParam("query", address)
                    .build().encode().toUriString();
            JsonNode root = get(uri);
            JsonNode docs = root == null ? null : root.path("documents");
            if (docs == null || !docs.isArray() || docs.isEmpty()) return null;
            JsonNode first = docs.get(0);
            double x = Double.parseDouble(first.path("x").asText("0")); // 경도
            double y = Double.parseDouble(first.path("y").asText("0")); // 위도
            return new double[]{x, y};
        } catch (Exception e) {
            log.warn("카카오 지오코딩 실패 ({}): {}", address, e.getMessage());
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
        return restClient.get()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "KakaoAK " + adminKey)
                .retrieve()
                .body(JsonNode.class);
    }
}
