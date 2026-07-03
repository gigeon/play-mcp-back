package play.mcp.back.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import play.mcp.back.common.BaseMap;
import play.mcp.back.domain.api.service.ApiService;

@Service
@RequiredArgsConstructor
public class KakaoTool {

    @Value("${api.kakao.url}")
    private String kakaoUrl;

    private final ApiService apiService;

    @Tool(description = """
        카카오 톡캘린더의 일정을 조회할 때 사용한다.
        조회할 시작 시간과 종료 시간을 ISO-8601 형식(예: 2026-07-03T00:00:00Z)으로 받아
        해당 기간 내의 카카오 일정 목록을 반환한다.
        """)
    public BaseMap getCalendarEvents(
            @ToolParam(description = "조회 시작 시간 (ISO-8601 형식)") String from,
            @ToolParam(description = "조회 종료 시간 (ISO-8601 형식)") String to
    ) {
        BaseMap param = new BaseMap();
        param.put("from", from);
        param.put("to", to);
        String token = apiService.getToken("Authorization");

        return apiService.callGet(kakaoUrl+"/events", param, token);
    }

    /**
     * 2. 톡캘린더 일정 등록 툴
     */
    @Tool(description = """
        카카오 톡캘린더에 새로운 일정을 등록(생성)할 때 사용한다.
        일정 제목과 시작 시간, 종료 시간(ISO-8601 형식)을 입력받아 카카오 캘린더에 추가한다.
        """)
    public BaseMap createCalendarEvent(
            @ToolParam(description = "일정 제목") String title,
            @ToolParam(description = "일정 시작 시간 (ISO-8601 형식)") String startAt,
            @ToolParam(description = "일정 종료 시간 (ISO-8601 형식)") String endAt
    ) {
        BaseMap param = new BaseMap();

        try {
            ObjectMapper objectMapper = new ObjectMapper();

            ObjectNode eventNode = objectMapper.createObjectNode();
            eventNode.put("title", title);

            ObjectNode timeNode = objectMapper.createObjectNode();
            timeNode.put("start_at", startAt);
            timeNode.put("end_at", endAt);
            timeNode.put("time_zone", "Asia/Seoul");

            eventNode.set("time", timeNode);

            param.put("event", eventNode.toString());

        } catch (Exception e) {
            e.printStackTrace();
        }

        String token = apiService.getToken("Authorization");

        return apiService.callPost(kakaoUrl+"/create/default/events", param, token);
    }
}
