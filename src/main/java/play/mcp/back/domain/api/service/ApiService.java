package play.mcp.back.domain.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import play.mcp.back.common.BaseMap;

import java.net.URI;
import java.util.Map;

@Service
public class ApiService {
    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ApiService(RestClient.Builder builder) {
        this.restClient = builder.build();
    }

    public BaseMap callGet(String url, BaseMap param) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(url);
        param.forEach((k, v) -> builder.queryParam(k, v));
        URI uri = builder.build().encode().toUri();   // 한글/특수문자 인코딩

        return restClient.get()
                .uri(uri)        // 완성된 URI를 통째로 넘김
                .retrieve()
                .body(new ParameterizedTypeReference<BaseMap>() {});
    }

    /**
     * Content-Type 이 부정확한 API(예: JSON 본문을 text/html 로 내려주는 일부 공공 API)를 위해
     * 응답 본문을 String 으로 받아 ObjectMapper 로 직접 파싱한다.
     */
    public BaseMap callGetAsMap(String url, BaseMap param) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(url);
        param.forEach((k, v) -> builder.queryParam(k, v));
        URI uri = builder.build().encode().toUri();

        String body = restClient.get()
                .uri(uri)
                .retrieve()
                .body(String.class);

        if (body == null || body.isBlank()) return new BaseMap();
        try {
            return objectMapper.readValue(body, BaseMap.class);
        } catch (Exception e) {
            throw new RuntimeException("응답 JSON 파싱 실패: " + e.getMessage(), e);
        }
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
