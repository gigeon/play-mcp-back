package play.mcp.back.domain.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.util.UriComponentsBuilder;
import play.mcp.back.common.BaseMap;

import java.net.URI;
import java.util.Map;

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

    public BaseMap callGet(String url, BaseMap param) {
        return restClient.get()
                .uri(buildUri(url, param))
                .retrieve()
                .body(BASE_MAP);
    }

    public BaseMap callPost(String url, BaseMap param) {
        return restClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .body(param)
                .retrieve()
                .body(BASE_MAP);
    }

    public String getToken(String key) {
         HttpServletRequest request = ((ServletRequestAttributes)
                RequestContextHolder.currentRequestAttributes()).getRequest();
         return request.getHeader(key);
    }

    public BaseMap callGet(String url, BaseMap param, String token) {
        return restClient.get()
                .uri(buildUri(url, param))
                .header("Authorization", token) // PlayMCP에서 받은 토큰 주입
                .retrieve()
                .body(BASE_MAP);
    }

    /**
     * Header에 토큰을 포함하는 POST 요청 (카카오 톡캘린더 등록용)
     * 카카오는 기본적으로 Form Url Encoded 방식을 사용합니다.
     */
    public BaseMap callPost(String url, BaseMap param, String token) {
        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        if (param != null) {
            param.forEach((k, v) -> formData.add(k, String.valueOf(v)));
        }

        return restClient.post()
                .uri(url)
                .header("Authorization", token) // PlayMCP에서 받은 토큰 주입
                .contentType(MediaType.APPLICATION_FORM_URLENCODED) // 카카오 표준 포맷
                .body(formData)
                .retrieve()
                .body(BASE_MAP);
    }

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
