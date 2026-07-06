package play.mcp.back.domain.realestate.model;

/**
 * 실거래 한 건. 금액 단위는 모두 <b>만원</b>.
 *
 * <ul>
 *   <li>전세: {@code deposit} 사용 (monthlyRent=0)</li>
 *   <li>월세: {@code deposit} + {@code monthlyRent}</li>
 *   <li>매매: {@code price} 사용</li>
 * </ul>
 *
 * @param type         주택유형 (아파트/오피스텔/빌라)
 * @param dealType     거래유형 (전세/월세/매매)
 * @param address      법정동 주소(구/동 등)
 * @param buildingName 단지/건물명
 * @param deposit      보증금(만원)
 * @param monthlyRent  월세(만원)
 * @param price        매매가(만원)
 * @param areaM2       전용면적(㎡)
 * @param dealYmd      거래연월 (YYYYMM)
 * @param lawdCd       법정동코드(5자리)
 */
public record Deal(
        String type,
        String dealType,
        String address,
        String buildingName,
        int deposit,
        int monthlyRent,
        int price,
        int areaM2,
        String dealYmd,
        String lawdCd
) {
    /** 이 거래가 대출한도(만원) 안에 들어오는지. 전세/월세는 보증금 기준, 매매는 매매가 기준. */
    public boolean withinLimit(int limit) {
        int cost = "매매".equals(dealType) ? price : deposit;
        return cost > 0 && cost <= limit;
    }
}
