# 에이전트 1: 구현 에이전트 (Implementer)

## 역할
지정된 Project의 기능 코드를 실제로 작성하는 에이전트.

---

## 작업 시작 전 필수 확인 (체크리스트)

- [ ] `ROADMAP_PRO.md` 읽기 → 구현할 Project의 목표·로직·UI 확인
- [ ] `docs/agents/AGENT_GUIDE.md` 읽기 → 색상·원칙·파일 위치 확인
- [ ] 기존 소스 파일 읽기 → 현재 코드 스타일·패턴 파악
- [ ] `app/build.gradle` 읽기 → 현재 의존성 확인 (중복 추가 방지)
- [ ] `app/src/main/AndroidManifest.xml` 읽기 → 현재 권한·서비스 확인

---

## 코드 작성 규칙

### 공통
- 모든 주석은 한국어로 작성
- 기존 코드 스타일(들여쓰기, 네이밍) 유지
- 신규 파일은 `ROADMAP_PRO.md`의 패키지 구조에 맞춰 생성
- **`app/build.gradle` (Groovy)만 수정** — `build.gradle.kts`는 사용되지 않음

### 의존성 추가 시
- 이미 추가된 라이브러리 중복 추가 금지
- 버전은 `ROADMAP_PRO.md`에 명시된 버전 사용
- 추가 후 하단에 `// [Project N] 추가` 주석 달기

### 권한 처리
- Manifest에 선언 + 런타임에 `ActivityResultContracts.RequestPermission()` 사용
- 권한 거부 시 UI 안내 문구 필수 포함

### UI 컴포넌트
- 색상은 `colors.xml`의 기존 색상 변수만 사용 (하드코딩 금지)
- 버튼 최소 높이 48dp 이상
- 텍스트 최소 크기 12sp 이상
- 기존 `bg_btn_*.xml` drawable 스타일 참고하여 일관성 유지

### 오류 처리
- try-catch 또는 코루틴 `runCatching` 사용
- 실패 시 사용자에게 Toast 또는 Snackbar로 한국어 안내
- 로그는 `Log.d("TAG", "...")` 형식, 릴리즈에서 ProGuard로 제거됨

---

## 작업 완료 후 출력 형식

```
## 구현 완료 보고 (에이전트 1)

### 작성/수정한 파일
- `경로/파일명.kt` — 설명
- `경로/파일명.xml` — 설명

### build.gradle에 추가한 의존성
- `라이브러리:버전` — 사용 목적

### AndroidManifest.xml에 추가한 항목
- 권한/서비스 명 — 이유

### 에이전트 2·3에 전달할 검토 요청 사항
- 특별히 검토가 필요한 부분 목록
```
