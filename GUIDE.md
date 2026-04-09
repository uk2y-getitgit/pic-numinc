# Smart Field Snap — 개발 지침서

> 갤럭시 현장 사진 파일명 자동 변경 + 폴더 정리 안드로이드 앱  
> 마지막 업데이트: 2026-04-10 | 빌드 경로: `android studio/app/build/outputs/apk/debug/app-debug.apk`

---

## 프로젝트 기본 정보

| 항목 | 값 |
|---|---|
| 패키지명 | `com.jaeuk.photorename` |
| minSdk | 26 (Android 8.0) |
| targetSdk | 34 |
| 언어 | Kotlin |
| 빌드 폴더 | `android studio/` ← Gradle 빌드는 이 폴더에서 실행 |
| 소스 폴더 | `app/` ← 실제 편집은 여기서 |

> **주의**: 소스(`app/`)와 빌드(`android studio/`) 두 폴더가 분리되어 있음.  
> 파일 수정 후 반드시 `android studio/` 폴더에도 동일 파일 복사해야 빌드에 반영됨.

---

## 핵심 파일 구조

```
app/src/main/
├── java/com/jaeuk/photorename/
│   ├── MainActivity.kt          ← UI 제어, 설정 저장, 폴더 정리 기능
│   └── FloatingService.kt       ← 백그라운드 서비스, 사진 감지+이름변경
├── res/
│   ├── layout/activity_main.xml ← 앱 UI 레이아웃
│   ├── drawable/                ← 버튼/카드 배경 (bg_btn_*.xml 등)
│   └── values/colors.xml        ← 다크 UI 색상 팔레트
└── AndroidManifest.xml
```

---

## SharedPreferences 키 목록 (`PhotoRenamePrefs`)

| 키 | 타입 | 설명 |
|---|---|---|
| `prefix` | String | 접두어 (기본값: `img`) |
| `site_name` | String | 현장명 |
| `counter` | Int | 현재 촬영 번호 (기본값: 1) |
| `folder_uri` | String | SAF 감시 폴더 URI |

---

## 핵심 기능 1 — 파일명 자동 변경 (`FloatingService.kt`)

### 동작 흐름
1. `ContentObserver`가 `MediaStore.Images.Media.EXTERNAL_CONTENT_URI` 전체 감시
2. 새 URI 감지 → `IS_PENDING=1`이면 1.5초 대기 후 재시도 (최대 4회)
3. 파일 경로가 선택된 감시 폴더 내에 있는지 확인
4. 갤럭시 카메라 파일명 패턴 확인 (아래 참조)
5. `DocumentsContract.renameDocument()` 로 이름 변경 → 카운터 +1

### 갤럭시 카메라 파일명 패턴 (이 중 하나면 이름 변경 대상)
```
20260409_213940.jpg   → \d{8}_\d{6}\.jpg
IMG_2026xxxx.jpg      → IMG_\d+.*\.jpg
20260409xxx.jpg       → .*\d{8}.*\.jpg
1234.jpg              → \d+\.jpg
```

### 생성 파일명 형식
```
현장명 있을 때: {site_name}-{prefix}-{001}.jpg   예) A현장-외벽-001.jpg
현장명 없을 때: {prefix}-{001}.jpg               예) img-001.jpg
```

### 중복 방지 메커니즘
- `processingUris` (Set): 동시에 같은 URI 처리 방지
- `completedUris` (ArrayDeque, 최대 100개): 완료된 URI 재처리 방지

---

## 핵심 기능 2 — 현장명 폴더 생성/정리 (`MainActivity.kt`)

### 동작 흐름
1. 버튼 클릭 → 현장명 + 감시 폴더 유효성 확인
2. 확인 다이얼로그 → "정리 시작" 선택
3. `Dispatchers.IO`에서 `DocumentFile.fromTreeUri()`로 감시 폴더 접근
4. 현장명과 동일한 하위 폴더 찾기 → 없으면 `createDirectory()` 생성
5. 감시 폴더 내 파일 순회 → `name.startsWith(siteName)` 인 파일만 선별
6. `moveFile()`: InputStream 복사 → 원본 삭제 (SAF 복사+삭제 방식, 전 API 호환)
7. 동일 파일명 이미 존재하면 스킵 (중복 방지)

### moveFile 핵심 코드 구조
```kotlin
contentResolver.openInputStream(source.uri) → openOutputStream(newFile.uri) → copyTo()
source.delete()  // 복사 성공 후 원본 삭제
// 실패 시 newFile.delete() 로 빈 파일 정리
```

---

## UI 구성 (위→아래 순서)

| 카드/영역 | 기능 |
|---|---|
| 헤더 | 앱명 + 서비스 상태 뱃지 (대기중/서비스중) |
| 감시 폴더 선택 | SAF OpenDocumentTree, DCIM 초기 위치 |
| 현장 정보 입력 | 현장명(etSiteName) + 접두어(etPrefix) |
| 파일명 미리보기 | 실시간 반영, 구성요소 칩 표시 |
| 촬영 번호 | 현재 카운터 + 번호 초기화 버튼 |
| 서비스 시작/중지 | Foreground Service 시작·중지 |
| **현장명 폴더 정리** | 폴더 생성+사진 이동 (청록색 카드) |

---

## 빌드 & 배포

```bash
# 빌드 (android studio 폴더에서)
cd "android studio"
./gradlew assembleDebug

# APK 경로
android studio/app/build/outputs/apk/debug/app-debug.apk
```

> 소스 수정 후 빌드 전 체크리스트:
> 1. `app/` 수정 완료
> 2. `android studio/` 에 동일 파일 복사
> 3. `./gradlew assembleDebug` 실행

---

## 권한 목록 (AndroidManifest.xml)

```xml
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />        <!-- API 33+ -->
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />    <!-- API 32 이하 -->
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />   <!-- API 28 이하 -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

---

## 주요 의존성 (`build.gradle.kts`)

```kotlin
implementation(libs.androidx.core.ktx)
implementation(libs.androidx.appcompat)
implementation(libs.material)
implementation(libs.kotlinx.coroutines.android)
implementation(libs.androidx.documentfile)     // SAF DocumentFile
```

---

## 자주 발생하는 빌드 오류

| 오류 | 원인 | 해결 |
|---|---|---|
| `resource color/purple_500 not found` | themes.xml이 기본 색상 참조 | themes.xml에서 @color/orange, @color/teal 등 앱 색상으로 교체 |
| `Unresolved reference 'bg_status_active'` | android studio/ drawable 누락 | app/drawable/ → android studio/drawable/ 복사 |
| `Unresolved reference 'count개'` | 한글이 변수명으로 인식됨 | `$count개` → `${count}개` 로 수정 |
| `Unable to access jarfile gradle-wrapper.jar` | 루트 폴더에서 실행 | `android studio/` 폴더에서 실행할 것 |

---

## 앱 설치 후 필수 설정

1. 사진 접근 권한 → **항상 허용**
2. 배터리 최적화 → **예외 앱 등록** (백그라운드 유지)
3. 앱 실행 → 감시 폴더 선택 (DCIM/Camera) → 서비스 시작

---

## Git 저장소

`https://github.com/uk2y-getitgit/pic-numinc.git` — master 브랜치
