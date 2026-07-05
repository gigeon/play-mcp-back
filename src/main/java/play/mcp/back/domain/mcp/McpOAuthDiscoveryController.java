//package play.mcp.back.domain.mcp;
//
//import org.springframework.beans.factory.annotation.Value;
//import org.springframework.web.bind.annotation.PostMapping;
//import org.springframework.web.bind.annotation.GetMapping;
//import org.springframework.web.bind.annotation.RequestBody;
//import org.springframework.web.bind.annotation.RestController;
//
//import java.util.HashMap;
//import java.util.List;
//import java.util.Map;
//
//@RestController
//public class McpOAuthDiscoveryController {
//
//    @Value("${playmcp.client-id}")
//    private String kakaoClientId;
//
//    @Value("${playmcp.client-secret}")
//    private String kakaoClientSecret;
//
//    @GetMapping("/.well-known/oauth-authorization-server")
//    public Map<String, Object> getOAuthMetadata() {
//        Map<String, Object> metadata = new HashMap<>();
//
//        metadata.put("issuer", "https://kauth.kakao.com");
//        metadata.put("authorization_endpoint", "https://kauth.kakao.com/oauth/authorize");
//        metadata.put("token_endpoint", "https://kauth.kakao.com/oauth/token");
//
//        metadata.put("registration_endpoint", "http://localhost:8000/register");
//
//        metadata.put("response_types_supported", List.of("code"));
//        metadata.put("grant_types_supported", List.of("authorization_code"));
//        metadata.put("token_endpoint_auth_methods_supported", List.of("client_secret_post"));
//        metadata.put("code_challenge_methods_supported", List.of("S256"));
//
//        return metadata;
//    }
//
//    @PostMapping("/register")
//    public Map<String, Object> registerClient(@RequestBody(required = false) Map<String, Object> request) {
//        Map<String, Object> response = new HashMap<>();
//
//        response.put("client_id", kakaoClientId);
//        response.put("client_secret", kakaoClientSecret);
//        response.put("client_id_issued_at", System.currentTimeMillis() / 1000);
//        response.put("client_secret_expires_at", 0); // 만료 없음
//
//        if (request != null && request.get("redirect_uris") != null) {
//            response.put("redirect_uris", request.get("redirect_uris"));
//        }
//
//        response.put("token_endpoint_auth_method", "client_secret_post");
//        response.put("grant_types", List.of("authorization_code"));
//        response.put("response_types", List.of("code"));
//
//        return response;
//    }
//}