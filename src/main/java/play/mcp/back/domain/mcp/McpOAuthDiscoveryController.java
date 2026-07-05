package play.mcp.back.domain.mcp;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class McpOAuthDiscoveryController {

    @GetMapping(value = "/.well-known/oauth-protected-resource", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> protectedResourceMetadata() {
        Map<String, Object> metadata = Map.of(
                "resource", "https://ktu-mcp-dev-lgg.playmcp-endpoint.kakaocloud.io/mcp",
                "authorization_servers", List.of("https://kauth.kakao.com"),
                "bearer_methods_supported", List.of("header"),
                "scopes_supported", List.of("talk_calendar")
        );
        return ResponseEntity.ok(metadata);
    }
}