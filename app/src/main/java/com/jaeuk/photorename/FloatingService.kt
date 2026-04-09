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

    // 현재 처리 중인 URI (동시 중복 처리 방지)
    private val processingUris = Collections.synchronizedSet(mutableSetOf<String>())
    // 처리 완료된 URI 캐시 (최대 100개, 재알림으로 인한 중복 처리 방지)
    private val completedUris = ArrayDeque<String>()

    companion object {
        const val CHANNEL_ID = "PhotoRenameChannel"
        const val NOTIFICATION_ID = 1001
        private const val MAX_COMPLETED_CACHE = 100
        private const val MAX_RETRY = 4          // IS_PENDING 재시도 최대 횟수
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
                "사진 자동 이름 변경",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "카메라 자동 이름 변경 서비스 알림입니다."
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
            .setContentTitle("📸 Smart Field Snap")
            .setContentText("사진 폴더 감시 중 — 새 사진 촬영 시 자동으로 이름이 변경됩니다.")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    // ContentObserver 등록 — MediaStore 전체 감시
    private fun setupPhotoObserver() {
        val handler = Handler(Looper.getMainLooper())
        photoObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                uri ?: return
                val uriStr = uri.toString()

                // 이미 처리 완료된 URI면 스킵
                synchronized(completedUris) {
                    if (uriStr in completedUris) return
                }

                // 처리 중인 URI면 스킵 (동시 중복 방지)
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

    // IS_PENDING 상태 확인 → 촬영 완료까지 재시도 (최대 MAX_RETRY회)
    private suspend fun renameWithRetry(uri: Uri, retryCount: Int) {
        delay(RETRY_DELAY_MS)

        when (renameNewPhoto(uri)) {
            RenameResult.SUCCESS -> {
                // 처리 완료 URI 캐시에 추가 (최대 100개 유지)
                synchronized(completedUris) {
                    completedUris.addLast(uri.toString())
                    while (completedUris.size > MAX_COMPLETED_CACHE) completedUris.removeFirst()
                }
            }
            RenameResult.PENDING -> {
                if (retryCount < MAX_RETRY) {
                    // 아직 촬영 중 — 재시도
                    renameWithRetry(uri, retryCount + 1)
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            applicationContext,
                            "⏱️ 사진이 아직 준비 중입니다. 잠시 후 다시 시도하세요.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            else -> { /* SKIP / FAIL: 별도 처리 없음 */ }
        }
    }

    private enum class RenameResult { SUCCESS, PENDING, SKIP, FAIL }

    // 실제 파일명 변경 처리
    private suspend fun renameNewPhoto(uri: Uri): RenameResult {
        try {
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
                if (!cursor.moveToFirst()) return RenameResult.PENDING

                val displayNameIdx = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                if (displayNameIdx < 0) return RenameResult.SKIP
                val displayName = cursor.getString(displayNameIdx) ?: return RenameResult.SKIP

                // IS_PENDING=1 이면 카메라가 아직 파일을 쓰는 중 → 재시도 필요
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val isPendingIdx = cursor.getColumnIndex(MediaStore.Images.Media.IS_PENDING)
                    if (isPendingIdx >= 0 && cursor.getInt(isPendingIdx) == 1) {
                        return RenameResult.PENDING
                    }
                }

                // 선택된 폴더 URI 확인 (폴더 미선택 시 스킵)
                val folderUriStr = prefs.getString(MainActivity.KEY_FOLDER_URI, null)
                    ?: return RenameResult.SKIP
                val folderUri = Uri.parse(folderUriStr)

                // 파일 경로가 선택된 폴더 내에 있는지 확인
                val filePath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                        .takeIf { it >= 0 }?.let { cursor.getString(it) } ?: ""
                } else {
                    cursor.getColumnIndex(MediaStore.Images.Media.DATA)
                        .takeIf { it >= 0 }?.let { cursor.getString(it) } ?: ""
                }

                val folderSegment = folderUri.lastPathSegment?.substringAfter(":") ?: ""
                if (filePath.isNotEmpty() && folderSegment.isNotEmpty() &&
                    !filePath.contains(folderSegment, ignoreCase = true)
                ) return RenameResult.SKIP

                val prefix = prefs.getString(MainActivity.KEY_PREFIX, "img") ?: "img"
                val siteName = prefs.getString(MainActivity.KEY_SITE_NAME, "") ?: ""

                // 이미 변경된 파일명이면 스킵
                val expectedStart = if (siteName.isNotEmpty()) "$siteName-$prefix-" else "$prefix-"
                if (displayName.startsWith(expectedStart)) return RenameResult.SKIP

                // 갤럭시 카메라 기본 파일명 패턴 확인 (20260409_213940.jpg 등)
                val isCameraFile =
                    displayName.matches(Regex("""\d{8}_\d{6}\.jpg""", RegexOption.IGNORE_CASE)) ||
                    displayName.matches(Regex(""".*\d{8}.*\.jpg""", RegexOption.IGNORE_CASE)) ||
                    displayName.matches(Regex("""IMG_\d+.*\.jpg""", RegexOption.IGNORE_CASE)) ||
                    displayName.matches(Regex("""\d+\.jpg""", RegexOption.IGNORE_CASE))

                if (!isCameraFile) return RenameResult.SKIP

                val counter = prefs.getInt(MainActivity.KEY_COUNTER, 1)
                val newName = buildFileName(siteName, prefix, counter)

                // DocumentsContract.renameDocument() 사용 — SAF 정식 API, O(1) 성능
                val treeDocId = DocumentsContract.getTreeDocumentId(folderUri)
                val fileDocId = "$treeDocId/$displayName"
                val docUri = DocumentsContract.buildDocumentUriUsingTree(folderUri, fileDocId)

                val resultUri = try {
                    DocumentsContract.renameDocument(contentResolver, docUri, newName)
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }

                return if (resultUri != null) {
                    prefs.edit().putInt(MainActivity.KEY_COUNTER, counter + 1).apply()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(applicationContext, "✓ $newName", Toast.LENGTH_SHORT).show()
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

    private fun sanitize(input: String): String =
        input.replace(Regex("""[/\\:*?"<>|\u0000]"""), "_")

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
            .setContentTitle("📸 Smart Field Snap")
            .setContentText("최신: $lastFile | 다음: %03d번".format(nextNumber))
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
