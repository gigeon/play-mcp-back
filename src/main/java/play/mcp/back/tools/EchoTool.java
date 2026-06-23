package play.mcp.back.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

@Service
public class EchoTool {

    @Tool(description = "입력으로 받은 문자열을 그대로 되돌려준다. 연결/동작 확인용.")
    public String echo(
            @ToolParam(description = "되돌려줄 메시지") String message) {
        return message;
    }

    @Tool(description = "서버가 살아있는지 확인한다. 항상 'pong' 을 반환한다.")
    public String ping() {
        return "pong";
    }
}
