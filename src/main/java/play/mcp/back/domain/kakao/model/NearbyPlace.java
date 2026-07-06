package play.mcp.back.domain.kakao.model;

/**
 * 매물 인근 장소(지하철역/학교) 한 건.
 *
 * @param name      장소명 (예: "역삼역")
 * @param category  카테고리명 (예: "지하철역", "학교")
 * @param distanceM 매물 좌표로부터의 거리(m)
 */
public record NearbyPlace(String name, String category, int distanceM) {
}
