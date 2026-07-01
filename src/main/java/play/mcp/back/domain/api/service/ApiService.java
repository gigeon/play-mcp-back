package play.mcp.back.domain.api.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.util.UriComponentsBuilder;
import play.mcp.back.common.BaseMap;

import java.net.URI;
import java.util.Map;

@Service
public class ApiService {
    private final RestClient restClient;

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

    public BaseMap callPost(String url, BaseMap param) {
        return restClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .body(param)
                .retrieve()
                .body(new ParameterizedTypeReference<BaseMap>() {});
    }

    public String getToken(String key) {
         HttpServletRequest request = ((ServletRequestAttributes)
                RequestContextHolder.currentRequestAttributes()).getRequest();
         return request.getHeader(key);
    }
}
