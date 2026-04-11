# Pro 버전 개발 로드맵

> 기준일: 2026-04-11  
> 현재 상태: Phase 1 무료 버전 완성 → 플레이스토어 등록 대기 중  
> 목표: 4개의 독립 Project를 순서대로 구현하여 Pro 버전 완성

---

## 전체 구조

```
[공통 인프라] ← 모든 Pro 기능이 공유하는 기반
     ↓
[Project 1] 현장명 자동 분석 폴더 정리
[Project 2] 선택 폴더 일괄 이름 변경
[Project 3] 클라우드 백업 연동
[Project 4] 팀원 사진 공유
```

Project 1, 2는 로그인 없이 Pro 결제만으로 사용 가능하게 설계.  
Project 3, 4는 로그인 필수.

---

## 공통 인프라 (모든 Project 시작 전 1회 구축)

### A. 구글 로그인 (Google Sign-In)

| 항목 | 내용 |
|------|------|
| 라이브러리 | `credentials:1.x` (Credential Manager API — 구식 GoogleSignIn 대체) |
| 연동 대상 | Firebase Authentication |
| 구현 범위 | 로그인 / 로그아웃 / 계정 삭제 (플레이스토어 정책 필수) |
| UI | 온보딩 화면 → "Google로 시작하기" 버튼 |

```
// 필요한 의존성
implementation 'androidx.credentials:credentials:1.3.0'
implementation 'com.google.android.libraries.identity.googleid:googleid:1.1.1'
implementation 'com.google.firebase:firebase-auth-ktx:23.x'
```

---

### B. 인앱 결제 (Google Play Billing)

| 항목 | 내용 |
|------|------|
| 라이브러리 | `billing:7.x` |
| 결제 모델 | 월정액 구독 또는 영구 구매 (1회 결제) — 결정 필요 |
| 구현 범위 | 결제 화면 / 구매 복원 / 결제 검증 |
| Pro 잠금 | 비결제 사용자 → 기능 진입 시 결제 유도 바텀시트 표시 |

```
// 필요한 의존성
implementation 'com.android.billingclient:billing-ktx:7.x'
```

**결제 흐름**
```
앱 실행 → BillingClient 연결 → 구매 이력 조회
→ 구매 있음: Pro 기능 잠금 해제
→ 구매 없음: 기능 진입 시 결제 유도
```

---

### C. 개인정보처리 동의 UI

| 항목 | 내용 |
|------|------|
| 시점 | 최초 실행 시 또는 로그인 전 |
| 필수 항목 | 서비스 이용약관 / 개인정보 처리방침 |
| 선택 항목 | 마케팅 수신 동의 |
| 저장 | SharedPreferences에 동의 날짜·버전 저장 |
| 계정 삭제 | 설정 → 계정 삭제 → 서버 데이터 즉시 삭제 (플레이스토어 의무) |

---

### D. Pro 공통 UI/UX 컴포넌트

- **페이월 바텀시트**: Pro 기능 진입 시 결제 유도 화면
- **Pro 배지**: 메인 화면 우상단에 Pro/Free 상태 표시
- **설정 화면**: 계정 정보 / 구독 관리 / 로그아웃 / 계정 삭제
- **온보딩 화면**: 최초 실행 시 앱 기능 소개 (3~4장 슬라이드)

---

---

## Project 1: 현장명 자동 분석 폴더 정리

> **목표**: 기존에 촬영된 사진의 파일명을 분석하여 현장명을 자동 추출하고, 해당 현장명 폴더로 자동 분류

### 구현 로직

#### 1-1. 파일명 패턴 분석
현재 앱의 파일명 규칙: `현장명_날짜_번호.확장자`  
분석 방식: 파일명을 `_` 구분자로 파싱 → 첫 번째 토큰을 현장명으로 추출

```kotlin
// 파일명 → 현장명 추출 예시
fun extractSiteName(fileName: String): String? {
    val parts = fileName.removeSuffix(".jpg").split("_")
    return if (parts.size >= 3) parts[0] else null  // 패턴 불일치 시 null
}
```

#### 1-2. 폴더 분류 로직
```
사진 전체 스캔
→ 각 사진에서 현장명 추출
→ 현장명별로 그룹핑
→ 사용자에게 미리보기 표시 ("A현장 32장, B현장 17장...")
→ 사용자 확인 후 → 폴더 이동 실행
```

#### 1-3. 분류 불가 항목 처리
- 패턴 불일치 사진 → "미분류" 폴더로 이동 또는 그대로 유지 (사용자 선택)
- 처리 결과 요약 화면 제공

### 필요한 UI
- 분석 대상 폴더 선택 (SAF - Storage Access Framework)
- 분석 결과 미리보기 리스트 (현장명 / 사진 수 / 예상 폴더 경로)
- 실행 / 취소 버튼
- 처리 진행 프로그레스바 (백그라운드 서비스 활용)

### 필요한 권한
```xml
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
```
→ 추가 권한 불필요 (기존 권한 범위 내)

### Pro 여부
결제 사용자 전용. 비결제 시 → 페이월 바텀시트 표시.

---

---

## Project 2: 선택 폴더 일괄 이름 변경

> **목표**: 사용자가 선택한 폴더 내 사진들의 이름을 일괄 변경. 현장명만 교체 / 전체 재명명 / 자동 넘버링 등 옵션 제공

### 구현 로직

#### 2-1. 변경 모드 3가지

| 모드 | 설명 | 예시 |
|------|------|------|
| 현장명만 교체 | 기존 파일명에서 현장명 부분만 새 이름으로 치환 | `A현장_240101_001.jpg` → `B현장_240101_001.jpg` |
| 전체 재명명 | 새 현장명 + 날짜 + 자동 넘버링으로 완전히 재생성 | `B현장_260411_001.jpg` |
| 넘버링 재정렬 | 현장명·날짜 유지, 번호만 1부터 재정렬 | `A현장_240101_001~050.jpg` |

#### 2-2. 처리 흐름
```
폴더 선택 (SAF)
→ 폴더 내 파일 목록 로드
→ 변경 모드 선택
→ 새 현장명 입력 (모드에 따라)
→ 미리보기: 변경 전/후 파일명 리스트
→ 사용자 확인 → 일괄 변경 실행
→ 결과 요약 (성공 N건 / 실패 N건)
```

#### 2-3. 안전 장치
- 변경 전 파일명 목록을 내부 DB(Room)에 임시 저장
- 변경 후 일정 시간 내 **실행 취소(Undo)** 기능 제공

### 필요한 UI
- 폴더 선택 버튼
- 모드 선택 라디오 버튼 또는 탭
- 새 현장명 입력 필드
- 변경 전/후 파일명 미리보기 RecyclerView (2열)
- 실행 / 취소 버튼
- 완료 후 Undo 스낵바 (30초 유효)

### 필요한 의존성 추가
```
// 실행 취소용 임시 저장
implementation 'androidx.room:room-ktx:2.6.x'
```

### Pro 여부
결제 사용자 전용.

---

---

## Project 3: 클라우드 백업 연동

> **목표**: 기기 내 사진을 Google Drive 또는 Naver MyBox에 자동/수동 백업

### 구현 로직

#### 3-1. 지원 클라우드 및 인증 방식

| 클라우드 | 인증 방식 | API |
|----------|----------|-----|
| Google Drive | OAuth2 (Google Sign-In 재활용) | Google Drive API v3 |
| Naver MyBox | OAuth2 (Naver 로그인 별도 추가) | Naver Cloud API |

> **구현 순서**: Google Drive 먼저 → 안정화 후 Naver MyBox 추가

#### 3-2. 백업 흐름
```
사용자 → 백업 대상 폴더 선택
→ 클라우드 연결 확인 (미연결 시 OAuth 인증)
→ 기존 백업 목록과 비교 (중복 업로드 방지)
→ 신규/변경 파일만 업로드 (증분 백업)
→ 백업 완료 알림
```

#### 3-3. 자동 백업 옵션
- Wi-Fi 연결 시 자동 백업 (WorkManager 활용)
- 백업 주기 설정: 매일 / 매주 / 수동만

#### 3-4. 필요한 의존성
```
// Google Drive
implementation 'com.google.api-client:google-api-client-android:2.x'
implementation 'com.google.apis:google-api-services-drive:v3-rev20240521-2.0.0'

// 백그라운드 동기화
implementation 'androidx.work:work-runtime-ktx:2.9.x'
```

### 필요한 권한 추가
```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

### 필요한 UI
- 클라우드 서비스 선택 화면 (Google Drive / Naver MyBox)
- 연결 상태 표시 (연결됨 / 미연결)
- 백업 대상 폴더 선택
- 자동 백업 설정 토글 + 주기 선택
- 백업 이력 화면 (날짜 / 파일 수 / 용량)
- 백업 진행 상태 알림 (Notification)

### 개인정보 처리 추가 항목
- 클라우드 연동 시 별도 동의 필요: "사진이 외부 클라우드에 저장됩니다"
- 앱에서 클라우드 토큰을 직접 저장하지 않음 (OAuth 토큰은 Google/Naver 관리)

### Pro 여부
로그인 + 결제 모두 필요.

---

---

## Project 4: 팀원 사진 공유

> **목표**: 클라우드 없이도 같은 프로젝트에 등록된 팀원들끼리 현장 사진을 실시간 공유

### 아키텍처

```
[팀원 A 기기] ←→ [Firebase / 백엔드 서버] ←→ [팀원 B 기기]
                         ↑
                   사진 메타데이터 + 공유 링크 저장
                   (실제 사진은 Firebase Storage 또는 Google Drive에 저장)
```

### 구현 로직

#### 4-1. 프로젝트(현장) 개념 도입
```
사용자 → 프로젝트 생성 ("○○현장 2026")
→ 초대 코드 또는 링크 생성
→ 팀원이 코드 입력 → 프로젝트 참여
→ 해당 프로젝트의 사진 공유 풀 접근 가능
```

#### 4-2. 공유 흐름
```
팀원 A가 사진 촬영 후 앱에서 업로드 선택
→ Firebase Storage에 사진 업로드
→ Firestore에 메타데이터 저장 (파일명 / 업로더 / 시간)
→ 같은 프로젝트 팀원들에게 FCM 푸시 알림
→ 팀원 B 앱에서 새 사진 확인 및 다운로드
```

#### 4-3. 필요한 Firebase 서비스

| 서비스 | 용도 |
|--------|------|
| Firebase Authentication | 로그인 (공통 인프라 재활용) |
| Firebase Firestore | 프로젝트 정보 / 팀원 목록 / 사진 메타데이터 |
| Firebase Storage | 공유 사진 파일 저장 |
| Firebase Cloud Messaging | 새 사진 업로드 시 팀원 알림 |

#### 4-4. 필요한 의존성
```
implementation platform('com.google.firebase:firebase-bom:33.x')
implementation 'com.google.firebase:firebase-firestore-ktx'
implementation 'com.google.firebase:firebase-storage-ktx'
implementation 'com.google.firebase:firebase-messaging-ktx'
```

### 필요한 UI
- 프로젝트 목록 화면 (내가 속한 현장 프로젝트 리스트)
- 프로젝트 생성 / 초대 코드 공유
- 초대 코드 입력으로 참여
- 공유 사진 갤러리 (프로젝트별 타임라인)
- 업로더 이름 / 업로드 시간 표시
- 다운로드 버튼 (기기에 저장)
- 푸시 알림: "○○님이 새 사진 3장을 올렸습니다"

### 개인정보 처리 추가 항목
- 팀원 공유 기능 사용 시 별도 동의: "사진이 공유 서버에 업로드됩니다"
- 계정 삭제 시: 내가 올린 사진 및 프로젝트 탈퇴 처리 API 구현 필수

### Pro 여부
로그인 + 결제 모두 필요. 프로젝트당 팀원 수 제한 고려 (예: Free 1명, Pro 무제한).

---

---

## 구현 순서 권장

```
[공통 인프라] → [Project 1] → [Project 2] → [Project 3] → [Project 4]
```

| 단계 | 내용 | 이유 |
|------|------|------|
| 공통 인프라 | 로그인 / 결제 / 동의 UI | 모든 Pro 기능의 기반 |
| Project 1 | 현장명 자동 분석 | 서버 없음, 구현 단순, 빠른 Pro 출시 가능 |
| Project 2 | 일괄 이름 변경 | 서버 없음, 기존 코드 확장 |
| Project 3 | 클라우드 백업 | 외부 API 연동 시작 |
| Project 4 | 팀원 공유 | 가장 복잡, Firebase 풀 스택 |

---

## 패키지 구조 (사전 설계)

```
com.jaeuk.photorename
├── ui/
│   ├── main/           # 기존 메인 화면
│   ├── onboarding/     # 온보딩 (공통 인프라)
│   ├── auth/           # 로그인 (공통 인프라)
│   ├── paywall/        # 결제 유도 (공통 인프라)
│   ├── settings/       # 설정·계정 (공통 인프라)
│   ├── analyze/        # Project 1
│   ├── batchrename/    # Project 2
│   ├── backup/         # Project 3
│   └── share/          # Project 4
├── data/
│   ├── local/          # Room DB (Project 2 Undo)
│   ├── remote/         # Firebase / Drive API
│   └── prefs/          # SharedPreferences
├── domain/
│   ├── model/          # 데이터 모델
│   └── usecase/        # 비즈니스 로직
└── service/
    ├── BackupWorker    # WorkManager (Project 3)
    └── SyncService     # FCM 수신 (Project 4)
```

---

---

## 에이전트 작업 방식

> 에이전트 작업을 요청할 때만 사용. 간단한 작업은 기본 Claude Code가 처리.

### 에이전트 호출 순서
```
[에이전트 1] 구현 → [에이전트 2] 코드검토 → [에이전트 3] UI검토 → [에이전트 4] 최종승인 → 빌드
```

### 지침서 위치
| 에이전트 | 파일 |
|----------|------|
| 운영 가이드 (공통 정보) | `docs/agents/AGENT_GUIDE.md` |
| 에이전트 1: 구현 | `docs/agents/agent_1_implementer.md` |
| 에이전트 2: 코드 검토 | `docs/agents/agent_2_code_reviewer.md` |
| 에이전트 3: UI/UX 검토 | `docs/agents/agent_3_frontend_reviewer.md` |
| 에이전트 4: 최종 확인 | `docs/agents/agent_4_final_approver.md` |

---

*최종 수정: 2026-04-11*
