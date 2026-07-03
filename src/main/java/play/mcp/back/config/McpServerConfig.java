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
 * 새 도구 클래스를 만들면 여기 toolObjects(...) 에 추가만 하면 된다.
 * (스프링 빈이라면 생성자 주입으로 받아서 넘기면 됨)
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
