# 현장 사진 파일명 자동 변경 앱 — 제작 가이드

## 프로젝트 개요

갤럭시 폰/태블릿에서 사진 촬영 시 파일명을 `img-001`, `img-002` 형식으로 자동 변경하고,
현장 이동 시 버튼 하나로 번호를 리셋하는 안드로이드 앱.

---

## 1단계: 환경 준비 (딱 한 번만)

### 필수 설치 목록
1. **Android Studio** — [developer.android.com/studio](https://developer.android.com/studio) 에서 다운로드
   - 실제로 쓰지 않아도 내부 SDK(빌드 도구)가 필요함
2. **Java JDK 17** — Android Studio 설치 시 자동 포함
3. **Claude Code** — 현재 사용 중인 이 도구

### 핸드폰 개발자 모드 활성화
1. 갤럭시 `설정` → `휴대전화 정보` → `소프트웨어 정보`
2. `빌드 번호`를 **7번 연속 터치** → "개발자 모드 활성화" 메시지 확인
3. `설정` → `개발자 옵션` → `USB 디버깅` 켜기

---

## 2단계: Claude Code에서 프로젝트 생성

### 프로젝트 초기 생성 프롬프트 (그대로 복사해서 사용)

```
새 안드로이드 Kotlin 프로젝트를 생성해줘. 패키지명은 com.jaeuk.photorename이야.

다음 기능을 포함해줘:
1. 사용자가 '접두어(예: img-)'를 입력할 수 있는 칸
2. 사진 촬영 시 '접두어-001'부터 순차적으로 번호가 붙으며 이름이 자동 변경
3. 화면 위에 항상 떠 있는 '플로팅 버튼' — 누르면 번호가 001로 초기화
4. 앱이 꺼져 있어도 백그라운드에서 계속 작동 (Foreground Service 사용)
5. 안드로이드 14(API 34) 환경에서 MediaStore API로 파일명 변경

다음 파일을 각각 분리해서 만들어줘:
- AndroidManifest.xml
- MainActivity.kt
- FloatingService.kt (플로팅 버튼 서비스)
- activity_main.xml (레이아웃)
- build.gradle (앱 수준)

안드로이드 13 이상의 READ_MEDIA_IMAGES 권한 요청 코드도 반드시 포함해줘.
```

---

## 3단계: 기능 추가 프롬프트 모음

### 현장명 포함 파일명
```
현재 코드에서 파일명 형식을 '[현장명]-[접두어]-[번호]' 조합으로 수정해줘.
예: 현장명=A현장, 접두어=img, 번호=001 → A현장-img-001
현장명도 사용자가 직접 입력할 수 있는 칸을 UI에 추가해줘.
```

### 일괄 변환 기능
```
기존에 찍어둔 사진들을 한꺼번에 이름 바꾸는 '일괄 변경' 기능을 추가해줘.
사진의 EXIF 데이터에서 촬영 날짜를 읽어와서 정렬 순서를 결정하고,
사용자가 지정한 접두어 형식으로 001부터 순서대로 이름을 변경해줘.
```

### 오류 수정 요청 (에러 발생 시)
```
아래 오류가 발생했어. 원인을 분석하고 수정해줘:
[adb logcat 또는 에러 메시지 붙여넣기]
```

---

## 4단계: 앱 빌드 (APK 파일 생성)

터미널에서 아래 명령어 실행:

```bash
# 프로젝트 폴더로 이동 후
./gradlew assembleDebug
```

결과 파일 위치:
```
app/build/outputs/apk/debug/app-debug.apk
```

---

## 5단계: 핸드폰에 설치

### 방법 A — 카카오톡/이메일 전송
1. `app-debug.apk` 파일을 카카오톡 '나와의 채팅'으로 전송
2. 핸드폰에서 파일 다운로드
3. 파일 실행 → "출처를 알 수 없는 앱" 경고 시 `허용` 선택
4. 설치 완료

### 방법 B — USB 직접 설치
1. USB 케이블로 PC와 핸드폰 연결
2. 터미널에서 실행:
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## 6단계: 앱 권한 설정 (중요!)

앱 첫 실행 시 반드시 허용해야 하는 권한:
- **사진 및 동영상 접근** → `항상 허용`
- **다른 앱 위에 표시** → `허용` (플로팅 버튼용)

---

## 기술 참고 정보

### 핵심 기술 스택
| 기술 | 용도 |
|---|---|
| ContentObserver | 새 사진 생성 감지 |
| MediaStore API | 파일명 변경 (Scoped Storage) |
| ContentResolver.update() | DISPLAY_NAME 수정 |
| Foreground Service | 백그라운드 동작 |
| SharedPreferences | 접두어/카운터 저장 |
| WindowManager | 플로팅 버튼 표시 |
| ExifInterface | 사진 메타데이터 읽기 |
| Room | 현장 목록 저장 |

### 권한 목록
```xml
<!-- AndroidManifest.xml에 들어가는 권한들 -->
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
```

---

## 자주 발생하는 문제 및 해결

| 증상 | 원인 | 해결 |
|---|---|---|
| 사진 이름이 안 바뀜 | 권한 미허용 | 앱 설정에서 사진 접근 권한 '항상 허용'으로 변경 |
| 앱 종료 시 작동 안 함 | 백그라운드 제한 | 배터리 최적화 예외 앱으로 등록 |
| 플로팅 버튼 안 보임 | 오버레이 권한 없음 | '다른 앱 위에 표시' 권한 허용 |
| 빌드 오류 | SDK 미설치 | Android Studio 재설치 후 SDK 업데이트 |

---

## 개발 팁

- **처음 목표**: 파일명 하나라도 바뀌면 성공. 완벽한 앱보다 동작하는 앱부터.
- **오류 발생 시**: `adb logcat` 출력을 복사해서 Claude에게 붙여넣으면 즉시 수정해줌.
- **기능 추가**: 한 번에 다 만들지 말고, 기본 기능 동작 확인 후 하나씩 추가.
- **APK 배포**: `Build → Generate Signed APK`로 서명된 APK 만들면 다른 직원 폰에도 설치 가능.
