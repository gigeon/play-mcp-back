package play.mcp.back.config;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import play.mcp.back.tools.EchoTool;
import play.mcp.back.tools.TimeTool;

/**
 * {@code @Tool} 이 붙은 빈들을 모아 MCP 서버에 등록한다.
 * <p>
 * 새 도구 클래스를 만들면 여기 toolObjects(...) 에 추가만 하면 된다.
 * (스프링 빈이라면 생성자 주입으로 받아서 넘기면 됨)
 */
@Configuration
public class McpServerConfig {

    @Bean
    public ToolCallbackProvider mcpTools(EchoTool echoTool, TimeTool timeTool) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(echoTool, timeTool)
                .build();
    }
}
