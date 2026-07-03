package play.mcp.back.domain.api.service;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import play.mcp.back.common.BaseMap;

import java.net.URI;

@Service
public class ApiService {
    private final RestClient restClient;

    public ApiService(RestClient.Builder builder) {
        this.restClient = builder.build();
    }

    /** 기본 GET 호출 (온통청년 등). */
    public BaseMap callGet(String url, BaseMap param) {
        return callGet(url, param, false);
    }

    /**
     * GET 호출.
     * @param jsonAccept true 이면 Accept: application/json 헤더를 붙인다 (사람인 API 용).
     */
    public BaseMap callGet(String url, BaseMap param, boolean jsonAccept) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(url);
        param.forEach((k, v) -> builder.queryParam(k, v));
        URI uri = builder.build().encode().toUri();   // 한글/특수문자 인코딩

        RestClient.RequestHeadersSpec<?> spec = restClient.get().uri(uri);
        if (jsonAccept) {
            spec = spec.accept(MediaType.APPLICATION_JSON);
        }
        return spec.retrieve()
                .body(new ParameterizedTypeReference<BaseMap>() {});
    }

    public BaseMap callPost(String url, BaseMap param) {
        return restClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .body(param)
                .retrieve()
                .body(new ParameterizedTypeReference<BaseMap>() {});
    }
}