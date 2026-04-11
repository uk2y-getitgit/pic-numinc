# 기본 ProGuard 규칙
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable

# Kotlin 코루틴
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# AndroidX / AppCompat
-keep class androidx.appcompat.** { *; }
-keep class androidx.core.** { *; }
-keep class androidx.documentfile.** { *; }

# 앱 내 데이터 모델 (필요 시 패키지 경로 추가)
-keep class com.jaeuk.photorename.** { *; }

# 디버그 로그 제거 (릴리즈 빌드)
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
