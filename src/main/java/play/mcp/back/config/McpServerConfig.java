package play.mcp.back.config;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import play.mcp.back.tools.YouthPoilyTool;
import play.mcp.back.tools.JobTool;

/**
 * {@code @Tool} 이 붙은 빈들을 모아 MCP 서버에 등록한다.
 * 새 도구 클래스를 만들면 여기 toolObjects(...) 에 추가만 하면 된다.
 */
@Configuration
public class McpServerConfig {

    @Bean
    public ToolCallbackProvider mcpTools(YouthPoilyTool youthPoilyTool,
                                         JobTool jobTool) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(youthPoilyTool, jobTool)
                .build();
    }
}