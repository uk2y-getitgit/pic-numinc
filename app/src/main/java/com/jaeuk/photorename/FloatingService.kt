package com.jaeuk.photorename

import android.app.*
import android.content.*
import android.database.ContentObserver
import android.net.Uri
import android.os.*
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.util.Collections

class FloatingService : Service() {

    private lateinit var prefs: SharedPreferences
    private lateinit var photoObserver: ContentObserver
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── 중복 처리 방지 ────────────────────────────────────────────────────────
    // processingUris: 현재 처리 시도 중인 URI (같은 URI로 동시에 여러 코루틴 실행 방지)
    private val processingUris = Collections.synchronizedSet(mutableSetOf<String>())
    // completedUris: 이름 변경 성공한 URI (재처리 방지, 최근 100개 유지)
    private val completedUris = ArrayDeque<String>()
    // ─────────────────────────────────────────────────────────────────────────

    companion object {
        const val CHANNEL_ID = "PhotoRenameChannel"
        const val NOTIFICATION_ID = 1001
        private const val MAX_COMPLETED_CACHE = 100
        private const val MAX_RETRY = 4          // IS_PENDING 해제 대기 최대 재시도 횟수
        private const val RETRY_DELAY_MS = 1500L // 재시도 간격 (ms)
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        setupPhotoObserver()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "사진 파일명 변경 서비스",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "백그라운드에서 사진 파일명을 자동 변경합니다."
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("📷 사진 자동 이름 변경 중")
            .setContentText("사진 촬영 시 파일명이 자동으로 변경됩니다.")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    // ── ContentObserver 등록 ──────────────────────────────────────────────────
    private fun setupPhotoObserver() {
        val handler = Handler(Looper.getMainLooper())
        photoObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                uri ?: return
                val uriStr = uri.toString()

                // 이미 이름 변경 완료된 URI → 스킵
                synchronized(completedUris) {
                    if (uriStr in completedUris) return
                }

                // 현재 처리 중인 URI → 스킵 (IS_PENDING=0 두 번째 알림도 여기서 막힘)
                // → 진행 중인 재시도 루프가 IS_PENDING 해제를 감지해서 처리
                if (!processingUris.add(uriStr)) return

                serviceScope.launch {
                    try {
                        renameWithRetry(uri, retryCount = 0)
                    } finally {
                        processingUris.remove(uriStr)
                    }
                }
            }
        }
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            photoObserver
        )
    }

    // ── IS_PENDING 해제 대기 재시도 루프 ──────────────────────────────────────
    // IS_PENDING=1(쓰는 중)이면 false를 반환 → 최대 MAX_RETRY회 재시도
    private suspend fun renameWithRetry(uri: Uri, retryCount: Int) {
        delay(RETRY_DELAY_MS)

        val result = renameNewPhoto(uri)

        when {
            result == RenameResult.SUCCESS -> {
                // 완료 캐시에 등록 (메모리 누수 방지: 최대 100개)
                synchronized(completedUris) {
                    completedUris.addLast(uri.toString())
                    while (completedUris.size > MAX_COMPLETED_CACHE) completedUris.removeFirst()
                }
            }
            result == RenameResult.PENDING && retryCount < MAX_RETRY -> {
                // 파일이 아직 쓰이는 중 → 재시도
                renameWithRetry(uri, retryCount + 1)
            }
            result == RenameResult.PENDING -> {
                // 최대 재시도 초과 → 사용자에게 알림
                withContext(Dispatchers.Main) {
                    Toast.makeText(applicationContext, "⚠️ 파일 처리 시간 초과. 다시 시도하세요.", Toast.LENGTH_SHORT).show()
                }
            }
            // SKIP / FAIL은 조용히 종료
        }
    }

    private enum class RenameResult { SUCCESS, PENDING, SKIP, FAIL }

    // ── 핵심: 파일명 변경 로직 ────────────────────────────────────────────────
    private suspend fun renameNewPhoto(uri: Uri): RenameResult {
        try {
            // Android 10+: IS_PENDING 포함 조회
            val projection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                arrayOf(
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.RELATIVE_PATH,
                    MediaStore.Images.Media.IS_PENDING
                )
            } else {
                arrayOf(
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATA
                )
            }

            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return RenameResult.PENDING  // 아직 DB에 없음 → 재시도

                val displayNameIdx = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                if (displayNameIdx < 0) return RenameResult.SKIP
                val displayName = cursor.getString(displayNameIdx) ?: return RenameResult.SKIP

                // ── IS_PENDING 체크 (핵심 수정) ──────────────────────────────
                // IS_PENDING=1: 카메라가 아직 파일을 쓰는 중 → 접근 불가 → 재시도
                // IS_PENDING=0: 파일 쓰기 완료 → 정상 처리
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val isPendingIdx = cursor.getColumnIndex(MediaStore.Images.Media.IS_PENDING)
                    if (isPendingIdx >= 0 && cursor.getInt(isPendingIdx) == 1) {
                        return RenameResult.PENDING
                    }
                }
                // ─────────────────────────────────────────────────────────────

                // 저장된 폴더 URI 확인
                val folderUriStr = prefs.getString(MainActivity.KEY_FOLDER_URI, null)
                    ?: return RenameResult.SKIP
                val folderUri = Uri.parse(folderUriStr)

                // 선택한 폴더의 파일인지 경로 검증
                val filePath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                        .takeIf { it >= 0 }?.let { cursor.getString(it) } ?: ""
                } else {
                    cursor.getColumnIndex(MediaStore.Images.Media.DATA)
                        .takeIf { it >= 0 }?.let { cursor.getString(it) } ?: ""
                }

                val folderSegment = folderUri.lastPathSegment?.substringAfter(":") ?: ""
                if (filePath.isNotEmpty() && folderSegment.isNotEmpty() &&
                    !filePath.contains(folderSegment, ignoreCase = true)) return RenameResult.SKIP

                val prefix = prefs.getString(MainActivity.KEY_PREFIX, "img") ?: "img"
                val siteName = prefs.getString(MainActivity.KEY_SITE_NAME, "") ?: ""

                // 이미 변환된 파일명이면 스킵
                val expectedStart = if (siteName.isNotEmpty()) "$siteName-$prefix-" else "$prefix-"
                if (displayName.startsWith(expectedStart)) return RenameResult.SKIP

                // 갤럭시 카메라 파일명 형식 검증 (20260409_213940.jpg 포함)
                val isCameraFile =
                    displayName.matches(Regex("\\d{8}_\\d{6}\\.jpg", RegexOption.IGNORE_CASE)) ||
                    displayName.matches(Regex(".*\\d{8}.*\\.jpg", RegexOption.IGNORE_CASE)) ||
                    displayName.matches(Regex("IMG_\\d+.*\\.jpg", RegexOption.IGNORE_CASE)) ||
                    displayName.matches(Regex("\\d+\\.jpg", RegexOption.IGNORE_CASE))

                if (!isCameraFile) return RenameResult.SKIP

                val counter = prefs.getInt(MainActivity.KEY_COUNTER, 1)
                val newName = buildFileName(siteName, prefix, counter)

                // ── 최적화: Document URI 직접 구성 (findFile 전체 탐색 없음) ───
                // treeDocId 예시: "primary:DCIM/Camera"
                // fileDocId 예시: "primary:DCIM/Camera/20260409_213940.jpg"
                // → O(1) 접근, 사진 수에 무관하게 일정한 속도
                val treeDocId = DocumentsContract.getTreeDocumentId(folderUri)
                val fileDocId = "$treeDocId/$displayName"
                val docUri = DocumentsContract.buildDocumentUriUsingTree(folderUri, fileDocId)

                val resultUri = try {
                    DocumentsContract.renameDocument(contentResolver, docUri, newName)
                } catch (e: Exception) {
                    null
                }

                return if (resultUri != null) {
                    prefs.edit().putInt(MainActivity.KEY_COUNTER, counter + 1).apply()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(applicationContext, "✅ $newName", Toast.LENGTH_SHORT).show()
                        updateNotification(newName, counter + 1)
                    }
                    RenameResult.SUCCESS
                } else {
                    RenameResult.FAIL
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return RenameResult.FAIL
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun sanitize(input: String): String =
        input.replace(Regex("""[/\\:*?"<>|\x00]"""), "_")

    private fun buildFileName(siteName: String, prefix: String, counter: Int): String {
        val number = "%03d".format(counter)
        val safeSite = sanitize(siteName)
        val safePrefix = sanitize(prefix).ifEmpty { "img" }
        return if (safeSite.isNotEmpty()) "$safeSite-$safePrefix-$number.jpg"
        else "$safePrefix-$number.jpg"
    }

    private fun updateNotification(lastFile: String, nextNumber: Int) {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("📷 사진 자동 이름 변경 중")
            .setContentText("마지막: $lastFile | 다음: %03d번".format(nextNumber))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        contentResolver.unregisterContentObserver(photoObserver)
        serviceScope.cancel()
    }
}
