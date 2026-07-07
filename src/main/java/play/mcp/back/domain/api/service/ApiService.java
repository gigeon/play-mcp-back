package play.mcp.back.domain.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import play.mcp.back.common.BaseMap;

import java.net.URI;

/**
 * 외부 REST API 호출 유틸. 쿼리파라미터를 인코딩해 GET 하고 응답을 {@link BaseMap} 으로 받는다.
 */
@Service
public class ApiService {

    /** BaseMap 응답 타입 참조(익명 클래스 반복 생성 방지). */
    private static final ParameterizedTypeReference<BaseMap> BASE_MAP =
            new ParameterizedTypeReference<>() {};

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ApiService(RestClient.Builder builder) {
        this.restClient = builder.build();
    }

    /** url + 쿼리파라미터 → 인코딩된 URI. 한글/특수문자를 안전하게 인코딩한다. */
    private URI buildUri(String url, BaseMap param) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(url);
        if (param != null) {
            param.forEach((k, v) -> builder.queryParam(k, v));
        }
        return builder.build().encode().toUri();
    }

    /** GET 호출 후 응답을 BaseMap(JSON 파싱)으로 반환한다. */
    public BaseMap callGet(String url, BaseMap param) {
        return restClient.get()
                .uri(buildUri(url, param))
                .retrieve()
                .body(BASE_MAP);
    }

    /**
     * GET 호출 후 <b>본문 문자열</b>을 직접 JSON 파싱한다.
     * 법정동 API 처럼 JSON 을 {@code text/html} 로 내려주는 경우에 쓴다.
     */
    public BaseMap callGetAsMap(String url, BaseMap param) {
        String body = restClient.get()
                .uri(buildUri(url, param))
                .retrieve()
                .body(String.class);

        if (body == null || body.isBlank()) return new BaseMap();
        try {
            return objectMapper.readValue(body, BaseMap.class);
        } catch (Exception e) {
            throw new RuntimeException("응답 JSON 파싱 실패: " + e.getMessage(), e);
        }
    }
}
