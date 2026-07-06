package play.mcp.back.config;

import play.mcp.back.common.McpTool;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * {@code @Tool} 이 붙은 빈들을 모아 MCP 서버에 등록한다.
 * <p>
 * {@link McpTool} 을 implements 한 모든 @Component 빈이 자동 수집되므로,
 * 새 도구 클래스는 {@code implements McpTool} 만 붙이면 별도 등록 없이 노출된다.
 */
@Configuration
public class McpServerConfig {

    @Bean
    public ToolCallbackProvider mcpTools(List<McpTool> tools) {
        // tools 리스트에는 McpTool을 implements한 모든 @Component 빈들이 자동으로 들어옵니다.
        return MethodToolCallbackProvider.builder()
                .toolObjects(tools.toArray()) // List를 Array로 변환하여 통째로 넘김
                .build();
    }
}
