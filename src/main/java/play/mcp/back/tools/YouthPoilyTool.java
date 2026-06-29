package play.mcp.back.tools;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
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
public class YouthPoilyTool {

    private final ApiService apiService;

    @Value("${api.baseUrl}")
    private String baseUrl;

    @Tool(description = """
        청년 정책 조회
        
        Args:
        lclsfNm: 참여권리/복지문화/주거/교육/일자리
    """)
    public BaseMap getYouthPoily(
            @ToolParam(description = "정책대분류") String lclsfNm
    ) {
        HttpServletRequest request = ((ServletRequestAttributes)
                RequestContextHolder.currentRequestAttributes()).getRequest();
        String apiKeyNm = request.getHeader("apiKeyNm");
        BaseMap param = new BaseMap();

        param.put("apiKeyNm", apiKeyNm);
        param.put("lclsfNm", lclsfNm);
        param.put("pageSize", 5);

        return apiService.callGet(baseUrl+"/getPlcy", param);
    }
}
