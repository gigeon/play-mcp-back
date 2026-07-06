# play-mcp-back — 청년 주거 추천 MCP · API 명세

청년 주거정책 조회 + **전월세보증금대출 한도 안에서 실거래 매물을 추천**하는 MCP 서버.
Spring Boot 3.4.5 / Spring AI MCP Server (WebMVC) / Streamable HTTP.

- 엔드포인트: `POST http://<host>:8000/mcp` (MCP Streamable HTTP)
- 금액 단위: **모두 만원**
- 실거래 소스 기본값: `molit`(국토부 실거래가 실 API). `application.yml` 또는 환경변수로 전환.

---

## 1. 연동한 외부 API

이 서버가 내부에서 호출하는 공공/상용 API 3종.

### 1-1. 온통청년 청년정책 API (YouthCenter)
청년 주거정책 목록 조회.

| 항목 | 값 |
|------|-----|
| Base URL | `https://www.youthcenter.go.kr/go/ythip` |
| Operation | `GET /getPlcy` |
| 인증 | 파라미터 `apiKeyNm` (MCP 요청 헤더 `apiKeyNm` 로 전달받아 주입) |
| 응답 | JSON |

**주요 요청 파라미터**

| 파라미터 | 설명 | 예 |
|----------|------|----|
| `apiKeyNm` | 발급 API 키 | `8481652f-...` |
| `lclsfNm` | 정책 대분류 | `주거` / `일자리` / `교육` / `복지문화` / `참여권리` |
| `pageSize` | 페이지 크기 | `5` |

**응답 핵심 필드**: `result.pagging.totCount`, `result.youthPolicyList[].plcyNm`(정책명),
`plcyExplnCn`(설명), `plcySprtCn`(지원내용), `lclsfNm`/`mclsfNm`(분류).

### 1-2. 국토교통부 실거래가 API (MOLIT / 공공데이터포털)
아파트·오피스텔·연립다세대의 매매·전월세 실거래 조회.

| 항목 | 값 |
|------|-----|
| Base URL | `http://apis.data.go.kr/1613000` |
| 인증 | 파라미터 `serviceKey` (공공데이터포털 **인코딩 키**, 재인코딩 금지) |
| 응답 | XML |
| ⚠️ 주의 | 기본 User-Agent 요청은 WAF 가 `400 Request Blocked` 처리 → **UA 헤더 필수** |

**주택유형 × 거래유형 → Operation 매핑**

| 유형 \ 거래 | 매매 | 전월세 |
|-------------|------|--------|
| 아파트 | `/RTMSDataSvcAptTrade/getRTMSDataSvcAptTrade` | `/RTMSDataSvcAptRent/getRTMSDataSvcAptRent` |
| 오피스텔 | `/RTMSDataSvcOffiTrade/getRTMSDataSvcOffiTrade` | `/RTMSDataSvcOffiRent/getRTMSDataSvcOffiRent` |
| 빌라(연립다세대) | `/RTMSDataSvcRHTrade/getRTMSDataSvcRHTrade` | `/RTMSDataSvcRHRent/getRTMSDataSvcRHRent` |

> 전세/월세는 하나의 "전월세" 엔드포인트로 함께 내려온다. 서버가 요청 거래유형에 맞춰
> `전세`=월세 0원, `월세`=월세 1원↑ 으로 걸러낸다.

**주요 요청 파라미터**

| 파라미터 | 설명 | 예 |
|----------|------|----|
| `serviceKey` | 인코딩 서비스키 | `dZID...%3D%3D` |
| `LAWD_CD` | 법정동코드 5자리(시군구) | `28275` |
| `DEAL_YMD` | 계약연월 YYYYMM | `202607` |
| `pageNo` / `numOfRows` | 페이지 | `1` / `100` |

- 서버는 신고 지연을 감안해 **최근 3개월**(`DEAL_YMD`)을 반복 조회한다.
- 응답 필드: `sggNm`(시군구), `umdNm`(동), `aptNm`/`offiNm`/`mhouseNm`(건물명),
  `deposit`/`monthlyRent`(전월세), `dealAmount`(매매), `excluUseAr`(전용면적),
  `dealYear`/`dealMonth`/`dealDay`, `floor`.

### 1-3. 카카오 로컬 API
매물 인근 지하철역·학교 검색(부가정보).

| 항목 | 값 |
|------|-----|
| Base URL | `https://dapi.kakao.com` |
| 인증 | 헤더 `Authorization: KakaoAK {REST/Admin 키}` |
| 주소→좌표 | `GET /v2/local/search/address.json?query=...` |
| 카테고리 검색 | `GET /v2/local/search/category.json?category_group_code=...&x=&y=&radius=&sort=distance` |
| 카테고리 코드 | `SW8`(지하철역), `SC4`(학교) |

- ⚠️ 앱에서 **"카카오맵" 제품을 활성화**해야 동작. 비활성 시
  `disabled OPEN_MAP_AND_LOCAL service` 오류.
- 키가 없거나 비활성이면 **빈 목록으로 graceful degrade**(추천 자체는 정상 동작).

---

## 2. MCP 툴 (외부 노출 기능)

MCP 클라이언트(Inspector 등)가 `tools/call` 로 호출하는 기능.

### 2-1. `recommendHousing` — 청년 주거 추천 (핵심)
대출 자격/한도를 판정하고, 그 **한도 안에서 거래된 실거래 매물**을 추천하며
각 매물 인근 지하철/학교를 붙여 반환한다.

**입력**

| 파라미터 | 타입 | 설명 | 예 |
|----------|------|------|----|
| `region` | string | 지역(시도+시군구) 또는 법정동코드 5자리 | `인천 서해구` |
| `housingType` | string | `아파트` / `오피스텔` / `빌라` | `오피스텔` |
| `dealType` | string | `전세` / `월세` / `매매` | `월세` |
| `age` | int | 나이(만) | `23` |
| `married` | boolean | 혼인 여부 | `false` |
| `income` | int | 연소득(만원) | `2700` |

**처리 흐름**
```
(나이·혼인·소득) → LoanRuleService.judge → 가능 대출상품 + 최대한도
      → RealEstateService.findDeals(region, 유형, 거래) : 국토부 실거래 최근 3개월
      → 한도 내 매물 필터(withinLimit) → 최대 10건
      → 각 매물 주소 지오코딩 → 반경 1km 지하철(SW8)/학교(SC4) 부착
```

**응답(JSON)**
```json
{
  "loan": {
    "products": [
      { "name": "청년전용 버팀목 전세자금대출", "maxLimit": 20000, "note": "..." }
    ],
    "maxLimit": 20000
  },
  "deals": [
    {
      "deal": {
        "type": "오피스텔", "dealType": "월세",
        "address": "서해구 청라동", "buildingName": "크리스탈뷰 2차",
        "deposit": 5000, "monthlyRent": 30, "price": 0,
        "areaM2": 30, "dealYmd": "202607", "lawdCd": "28275"
      },
      "subways": [ { "name": "청라국제도시역", "category": "지하철역", "distanceM": 850 } ],
      "schools": [ { "name": "청라초등학교", "category": "학교", "distanceM": 420 } ]
    }
  ]
}
```
> 카카오맵 미활성 시 `subways`/`schools` 는 `[]`.

### 2-2. `getYouthPoily` — 청년정책 조회
온통청년 API 로 대분류별 청년정책 목록을 조회한다.

| 파라미터 | 타입 | 설명 |
|----------|------|------|
| `lclsfNm` | string | 정책 대분류: `주거`/`일자리`/`교육`/`복지문화`/`참여권리` |

- 인증 키는 **MCP 요청 헤더 `apiKeyNm`** 에서 읽는다.
- 응답: 온통청년 원본 JSON(`result.youthPolicyList[]` 등).

### 2-3. 유틸 툴
| 툴 | 설명 |
|----|------|
| `currentTime` | 현재 시각(타임존/ISO/epoch) |
| `echo` | 입력 문자열 그대로 반환 |
| `ping` | 헬스 체크(`pong`) |

---

## 3. 전월세보증금대출 규칙 (LoanRuleService)

`judge(age, married, income, deposit, region)` 가 아래 카탈로그를 순회하며
**나이·혼인·연소득·보증금 상한**을 모두 통과하는 상품만 반환한다.
(⚠️ 수치는 주택도시기금 공고 **근사치** — 실제 서비스 전 최신 기준 검증 필요.)

| 상품 | 최대나이 | 최대연소득 | 최대보증금 | 최대한도 | 기혼허용 |
|------|:---:|:---:|:---:|:---:|:---:|
| 중소기업취업청년 전월세보증금대출 | 34 | 3,500 | 20,000 | 10,000 | ✕ |
| 청년전용 버팀목 전세자금대출 | 34 | 5,000 | 30,000 | 20,000 | ✕ |
| 버팀목 전세자금대출 | 제한없음 | 5,000 | 30,000 | 12,000 | ○ |

> 알려진 한계: 기혼 + 연소득 5천 초과 시 통과 상품이 0이 될 수 있다(신혼부부 전용 상품 미포함).

---

## 4. 지원 지역(법정동코드 매핑)

`LawdCode.resolve(region)` — 시도를 먼저 판별한 뒤 시군구를 찾는다. 5자리 숫자 직접 입력도 허용.

- **서울** 25개 자치구 전체
- **인천** 중구/동구/미추홀구/연수구/남동구/부평구/계양구/**서해구**/**검단구**/강화군/옹진군
  - ※ 2026년 인천 서구가 **서해구(28275)·검단구(28290)** 로 분구. 옛 서구(28260)는 데이터 없음.
- **부산** 16개 구·군 전체

그 외 지역은 매핑이 없어 `deals` 가 빈 목록으로 반환된다.

---

## 5. 설정 / 환경변수

`application.yml` 기본값이 실 API(molit)로 잡혀 있어 별도 설정 없이 동작한다.
환경변수로 덮어쓸 수 있다.

| 키 (yml) | 환경변수 | 기본/용도 |
|----------|----------|-----------|
| `api.key` | — | 온통청년 API 키 |
| `realestate.source` | `REALESTATE_SOURCE` | `molit`(실 API) / `sample`(번들 샘플) |
| `realestate.serviceKey` | `REALESTATE_SERVICE_KEY` | 공공데이터포털 인코딩 키 |
| `realestate.baseUrl` | — | `http://apis.data.go.kr/1613000` |
| `kakao.adminKey` | `KAKAO_ADMIN_KEY` | 카카오 REST/Admin 키(카카오맵 활성화 필요) |
| `server.port` | — | `8000` |

**실행**
```bash
java -jar build/libs/play-mcp-back-0.0.1.jar   # 환경변수 없이도 molit 실 API 동작
```

**MCP Inspector 테스트**
```bash
npx @modelcontextprotocol/inspector
# Transport: Streamable HTTP,  URL: http://localhost:8000/mcp
# Tools → recommendHousing → 파라미터 입력 → Run Tool
```
