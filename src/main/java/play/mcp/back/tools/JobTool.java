package play.mcp.back.tools;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import play.mcp.back.common.BaseMap;
import play.mcp.back.domain.api.service.ApiService;

@Service
@RequiredArgsConstructor
public class JobTool {

    private final ApiService apiService;

    @Value("${api.saramin.baseUrl}")
    private String baseUrl;

    @Tool(description = """
        채용 공고 검색 (사람인)
        
        Args:
        keyword: 검색 키워드 (예: 백엔드, 신입 개발자, 마케팅)
    """)
    public BaseMap searchJobs(
            @ToolParam(description = "검색 키워드") String keyword
    ) {
        HttpServletRequest request = ((ServletRequestAttributes)
                RequestContextHolder.currentRequestAttributes()).getRequest();
        // 사람인 access-key 는 헤더 'saramin-access-key' 로 전달받는다.
        String accessKey = request.getHeader("saramin-access-key");

        BaseMap param = new BaseMap();
        param.put("access-key", accessKey);
        param.put("keyword", keyword);
        param.put("count", 5);   // 출력 건수

        // 사람인은 Accept: application/json 이어야 JSON 으로 응답한다.
        return apiService.callGet(baseUrl, param, true);
    }
}