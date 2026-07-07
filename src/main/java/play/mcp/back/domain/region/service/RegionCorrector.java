package play.mcp.back.domain.region.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import play.mcp.back.domain.realestate.service.LawdCode;

import java.util.List;

/**
 * 지역명 오타 보정. 하드코딩 규칙 대신 <b>법정동코드 API 에서 시군구 목록을 가져와</b>
 * 입력한 시군구 토큰과 <b>편집거리(유사도)</b>가 가장 가까운 이름으로 맞춰준다.
 * 예) "대구 달서그" → 대구 시군구 목록 중 "달서구"(거리 1) 로 보정.
 *
 * <p>보정은 <b>기존 방식으로 해결되지 않을 때만</b> 수행한다(정상 입력은 그대로 통과).</p>
 */
@Service
@RequiredArgsConstructor
public class RegionCorrector {

    private final RegionCodeService regionCodeService;

    /**
     * 지역명을 보정해 반환한다. 보정 불필요/불가 시 입력을 그대로(trim) 반환한다.
     */
    public String correct(String region) {
        if (region == null || region.isBlank()) {
            return region;
        }
        String r = region.trim();

        // 1) 하드코딩 맵으로 이미 해결되면 그대로 둔다(서울/인천/부산 + 인천 분구 등).
        if (LawdCode.resolve(r) != null) return r;

        // 2) 시도 + 시군구 토큰 분리 (시도가 있어야 후보 목록을 가져올 수 있다).
        //    시군구는 시도 바로 뒤(2번째) 토큰이다. (뒤에 동이 붙어도 시군구는 2번째)
        String[] tokens = r.split("\\s+");
        if (tokens.length < 2 || !regionCodeService.isSidoToken(tokens[0])) {
            return r;
        }
        String guToken = tokens[1];

        // 3) 해당 시도의 시군구 목록을 가져온다. 이미 정확한 시군구명이면 보정 불필요.
        List<String> candidates = regionCodeService.sigunguNamesOf(tokens[0]);
        if (candidates.isEmpty() || candidates.contains(guToken)) {
            return r;
        }

        // 4) 가장 유사한(편집거리 최소) 시군구명 선택 — 충분히 유사할 때만 보정.
        String best = null;
        int bestDist = Integer.MAX_VALUE;
        for (String c : candidates) {
            int d = levenshtein(guToken, c);
            if (d < bestDist) {
                bestDist = d;
                best = c;
            }
        }
        if (best != null && bestDist <= Math.max(1, guToken.length() / 2)) {
            tokens[1] = best;
            return String.join(" ", tokens);
        }
        return r;
    }

    /** 두 문자열의 편집거리(삽입/삭제/치환). */
    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = cur; cur = tmp;
        }
        return prev[b.length()];
    }
}
