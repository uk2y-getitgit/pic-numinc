# 에이전트 운영 가이드

> 이 폴더의 에이전트는 **사용자가 명시적으로 에이전트 작업을 요청할 때만** 사용합니다.  
> 간단한 수정·질문은 기본 Claude Code가 직접 처리합니다.

---

## 에이전트 구성

| 순서 | 에이전트 | 파일 | 역할 |
|------|----------|------|------|
| 1 | 구현 에이전트 | `agent_1_implementer.md` | 기능 코드 작성 |
| 2 | 코드 검토 에이전트 | `agent_2_code_reviewer.md` | 로직 오류·누락 점검 |
| 3 | 프론트엔드 검토 에이전트 | `agent_3_frontend_reviewer.md` | UI/UX 사용자 관점 점검 |
| 4 | 최종 확인 에이전트 | `agent_4_final_approver.md` | 빌드 전 최종 승인 |

---

## 작업 흐름

```
사용자 요청
    ↓
[에이전트 1] 구현
    ↓
[에이전트 2] 코드 검토 → 문제 발견 시 에이전트 1에 수정 지시
    ↓
[에이전트 3] UI/UX 검토 → 문제 발견 시 에이전트 1에 수정 지시
    ↓
[에이전트 4] 최종 확인 → 승인 시 사용자에게 빌드 여부 문의
    ↓
사용자 승인 → 빌드 실행
```

---

## 프로젝트 핵심 정보 (에이전트 공통 참조)

### 앱 정보
- 패키지명: `com.jaeuk.photorename`
- 언어: Kotlin
- 빌드 도구: Gradle (Groovy DSL) — `app/build.gradle` 사용 (`build.gradle.kts` 아님)
- 타겟: Android 16 (API 36), minSdk 26

### 색상 팔레트 (colors.xml 기준)
| 용도 | 색상 | 값 |
|------|------|-----|
| 배경 | `@color/bg` | #0E0F13 |
| 카드 배경 | `@color/card` | #1F2230 |
| 주요 텍스트 | `@color/text_primary` | #E8EAF0 |
| 보조 텍스트 | `@color/text_dim` | #7A7F96 |
| 주 강조색 (버튼) | `@color/orange` | #FF6B00 |
| 성공 | `@color/green` | #00C851 |
| 경고/오류 | `@color/red_accent` | #FF3B3B |
| 폴더 기능 | `@color/teal` | #00BCD4 |

### 핵심 원칙
- Scoped Storage 준수 (`MANAGE_EXTERNAL_STORAGE` 금지)
- 권한은 실행 시점에 최소한으로 요청
- 사용자 데이터 외부 서버 전송 금지 (Zero-Data-Collection) — Pro 클라우드 기능 제외
- 모든 코드 주석은 한국어
- `app/build.gradle` (Groovy) 파일만 수정할 것 (`build.gradle.kts` 무시)

### 주요 파일 위치
- 빌드 설정: `app/build.gradle`
- 매니페스트: `app/src/main/AndroidManifest.xml`
- 색상: `app/src/main/res/values/colors.xml`
- 테마: `app/src/main/res/values/themes.xml`
- Pro 로드맵: `ROADMAP_PRO.md`
