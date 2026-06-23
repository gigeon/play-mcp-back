package play.mcp.back.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

@Service
public class TimeTool {

    /** 도구의 구조화된 응답. 필드명이 그대로 JSON 키가 된다. */
    public record TimeResult(String timezone, String isoTime, long epochMillis) {
    }

    @Tool(description = "지정한 타임존의 현재 시각을 반환한다. timezone 미입력 시 Asia/Seoul 사용.")
    public TimeResult currentTime(
            @ToolParam(required = false,
                    description = "IANA 타임존 ID (예: Asia/Seoul, UTC, America/New_York)")
            String timezone) {

        ZoneId zone = (timezone == null || timezone.isBlank())
                ? ZoneId.of("Asia/Seoul")
                : ZoneId.of(timezone);

        ZonedDateTime now = ZonedDateTime.now(zone);
        return new TimeResult(
                zone.getId(),
                now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                now.toInstant().toEpochMilli());
    }
}
