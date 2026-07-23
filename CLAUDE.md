# GLOW MCE + Data 360 Workspace

이 저장소는 GLOW 브랜드의 Marketing Cloud Engagement(MCE) + Salesforce Data 360(Data Cloud)
작업 공간입니다. 모든 세션은 아래 지침을 따릅니다.

## 필수 지침 (자동 로드)

@docs/GLOW_Project_Instructions.md
@docs/GLOW_Journey_I2Message_Instructions.md

## 응답 스타일

- 항상 한국어 존댓말로 답한다.
- 작업 과정(도구 호출 나열)은 노출하지 않고 결과만 정리해 전달한다.
- 마케팅 결론을 먼저, 기술 상세(DMO명·SQL·API)는 요청 시에만.

## 연결 구성

- **MCE (SFMC)**: `Claude_MCP_2` 커넥터의 `sfmc_*` 도구 사용. 토큰이 만료되면
  사용자에게 claude.ai 커넥터 설정에서 재연결을 요청할 것.
- **Data 360**: `.mcp.json`의 `data360` MCP 서버 (search / payload_examples / execute 3개 파사드,
  `execute`는 `toolName` + `paramsJson`(JSON 문자열) 인자 사용).
  - JAR은 SessionStart 훅이 빌드: 업스트림 소스 + `d360-overlay/` 오버레이(코드 수정분) 적용.
  - codeload 타르볼이 프록시에서 403이면 훅이 `git clone`으로 폴백함.
  - 세션 시작 시점에 JAR이 없어 서버 로드가 실패한 경우, stdio로 직접 구동해 사용 가능
    (이전 세션에서 `.data360/d360.py` 드라이버 사용 사례 있음).
- **i2message (카카오 친구톡)**: data360 서버의 `d360_i2message_register_friendtalk_template`,
  `d360_i2message_validate_journey_activity` 도구 사용. 인증은 HAR 자동 추출
  (`I2MESSAGE_HAR_PATH` 환경변수, 기본 `/home/user/mcp/mc-session.har`) —
  사용자에게 fuel2token/MID/accessToken을 채팅으로 요청하지 말 것.
  - HAR은 세션마다 새로 업로드받아 `/home/user/mcp/mc-session.har`에 배치 (토큰 만료 있음).
  - HAR 파일은 절대 커밋 금지 (.gitignore에 `*.har` 등록됨).
  - 필요 도메인: `stg.mc.api.i2message.io`, `stg.mc.receive.i2message.io` (환경 허용 목록 등록됨).

## 현재 BU 주요 값

- SFMC REST 호스트: `mc792x8fthwpxhp4f3pcghck3ls4.rest.marketingcloudapis.com`, MID `526001599`
- 이메일 액티비티 기본값: sendClassificationId `c16f1994-077c-eb11-ba34-f40343d01fd8`,
  senderProfileId `bf6f1994-077c-eb11-ba34-f40343d01fd8`,
  deliveryProfileId `c06f1994-077c-eb11-ba34-f40343d01fd8`, publicationListId `14`
- 성과 분석 데이터: `Journey_Activity_Performance_Summary_v2`,
  `Journey_Revenue_ROI_Promo_Benchmark_v2` 기반 (쿼리 시 실제 테이블명은
  `Journey_Activity_Performance_Summary_v2__dll`,
  `Journey_Revenue_ROI_Promo_Benchmark_v2__dll` — v2가 최신 기준, 구버전 `__dlm`은 사용하지 않음)

## 작업 이력 (2026-07-16 기준)

- 이메일 콘텐츠 `GLOW_SunCare_v1` 생성 (asset id 52204, legacyId 18755).
- 저니 `GLOW_SunCare_Retention` Draft 완성 (초판은 사용자 측에서 삭제, 재생성본):
  - ID `5d9ddbc7-682e-4c9b-a22f-9169b0ccfd17`, key `64a3cf2a-d56d-44ea-9a01-4febed660a86`, 버전 1
  - 진입 DE `GLOW_SunCare_Launch_Target` (id `f89f04a0-9c63-f111-9079-5cba2c19c328`)
  - Event Definition `DEAudience-64a3cf2a-d56d-44ea-9a01-4febed660a86`
    (id `261efa14-e45f-4689-800b-cf90cd78bc9e`, 매일 16:00 KST, 2026-07-17~12-31)
  - 흐름: 이메일(GLOW_SunCare_v1) → 3일 대기 → 오픈 분기 → 미오픈자만 친구톡
  - 친구톡 템플릿 `af42140b-86c9-42f8-bb40-80a762d6b7ee`, 최종 validate HTTP 200 완료
  - 활성화는 사용자 명시 요청 전까지 금지 (Draft 유지)
