package com.jaeuk.photorename

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var etPrefix: EditText
    private lateinit var etSiteName: EditText
    private lateinit var tvFolderPath: TextView
    private lateinit var tvNextFileName: TextView
    private lateinit var btnSelectFolder: Button
    private lateinit var btnStartService: Button
    private lateinit var btnStopService: Button
    private lateinit var btnResetCounter: Button

    companion object {
        const val PREFS_NAME = "PhotoRenamePrefs"
        const val KEY_PREFIX = "prefix"
        const val KEY_SITE_NAME = "site_name"
        const val KEY_COUNTER = "counter"
        const val KEY_FOLDER_URI = "folder_uri"    // SAF 폴더 URI (영구 권한)
        const val DEFAULT_PREFIX = "img"
    }

    // 권한 요청 결과 처리
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            startPhotoService()
        } else {
            Toast.makeText(this, "사진 접근 권한이 필요합니다.\n설정 > 앱 > 권한에서 허용해주세요.", Toast.LENGTH_LONG).show()
        }
    }

    // SAF 폴더 선택기 결과 처리
    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@registerForActivityResult

        // 영구 읽기/쓰기 권한 획득
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )

        // URI 저장
        prefs.edit().putString(KEY_FOLDER_URI, uri.toString()).apply()

        // 화면에 경로 표시
        updateFolderDisplay(uri)
        Toast.makeText(this, "폴더 선택 완료!", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        etPrefix = findViewById(R.id.etPrefix)
        etSiteName = findViewById(R.id.etSiteName)
        tvFolderPath = findViewById(R.id.tvFolderPath)
        tvNextFileName = findViewById(R.id.tvNextFileName)
        btnSelectFolder = findViewById(R.id.btnSelectFolder)
        btnStartService = findViewById(R.id.btnStartService)
        btnStopService = findViewById(R.id.btnStopService)
        btnResetCounter = findViewById(R.id.btnResetCounter)

        // 저장된 값 불러오기
        etPrefix.setText(prefs.getString(KEY_PREFIX, DEFAULT_PREFIX))
        etSiteName.setText(prefs.getString(KEY_SITE_NAME, ""))

        // 저장된 폴더 URI 복원
        prefs.getString(KEY_FOLDER_URI, null)?.let { uriStr ->
            updateFolderDisplay(Uri.parse(uriStr))
        }

        updateNextFileDisplay()

        // 입력값 변경 시 다음 파일명 업데이트
        val watcher = object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = updateNextFileDisplay()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }
        etPrefix.addTextChangedListener(watcher)
        etSiteName.addTextChangedListener(watcher)

        // 폴더 선택 버튼 — DCIM 폴더를 초기 위치로 열기
        btnSelectFolder.setOnClickListener {
            val initialUri = Uri.parse(
                "content://com.android.externalstorage.documents/tree/primary%3ADCIM"
            )
            folderPickerLauncher.launch(initialUri)
        }

        // 서비스 시작 버튼
        btnStartService.setOnClickListener {
            if (prefs.getString(KEY_FOLDER_URI, null) == null) {
                Toast.makeText(this, "먼저 감시 폴더를 선택해주세요.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            saveSettings()
            checkAndRequestPermissions()
        }

        // 서비스 중지 버튼
        btnStopService.setOnClickListener {
            stopService(Intent(this, FloatingService::class.java))
            Toast.makeText(this, "서비스 중지됨 — 자동 이름 변경이 꺼졌습니다.", Toast.LENGTH_SHORT).show()
        }

        // 번호 초기화 버튼 (다음 현장 이동 시)
        btnResetCounter.setOnClickListener {
            val siteName = etSiteName.text.toString().trim()
            val prefix = etPrefix.text.toString().trim().ifEmpty { DEFAULT_PREFIX }
            val previewName = if (siteName.isNotEmpty()) "$siteName-$prefix-001.jpg"
                              else "$prefix-001.jpg"

            AlertDialog.Builder(this)
                .setTitle("번호 초기화")
                .setMessage("현재 입력된 현장명으로 001부터 시작합니다.\n\n첫 번째 파일명: $previewName\n\n서비스가 실행 중이면 즉시 적용됩니다.")
                .setPositiveButton("초기화") { _, _ ->
                    // 현장명·접두어 즉시 저장 → 서비스 재시작 없이 바로 적용
                    saveSettings()
                    prefs.edit().putInt(KEY_COUNTER, 1).apply()
                    updateNextFileDisplay()
                    Toast.makeText(this, "✅ 초기화 완료: $previewName 부터 시작", Toast.LENGTH_LONG).show()
                }
                .setNegativeButton("취소", null)
                .show()
        }
    }

    // 선택된 폴더 경로를 화면에 표시
    private fun updateFolderDisplay(uri: Uri) {
        val doc = DocumentFile.fromTreeUri(this, uri)
        val displayPath = doc?.uri?.path
            ?.replace("/tree/primary:", "/내장 저장공간/")
            ?.replace(":", "/")
            ?: uri.toString()

        tvFolderPath.text = displayPath
        tvFolderPath.setTextColor(android.graphics.Color.parseColor("#212121"))
    }

    // 현재 설정값 저장
    private fun saveSettings() {
        val prefix = etPrefix.text.toString().trim().ifEmpty { DEFAULT_PREFIX }
        val siteName = etSiteName.text.toString().trim()
        prefs.edit()
            .putString(KEY_PREFIX, prefix)
            .putString(KEY_SITE_NAME, siteName)
            .apply()
    }

    // 다음 촬영 파일명 표시 (현재 누적 번호 기준)
    private fun updateNextFileDisplay() {
        val prefix = etPrefix.text.toString().trim().ifEmpty { DEFAULT_PREFIX }
        val siteName = etSiteName.text.toString().trim()
        val counter = prefs.getInt(KEY_COUNTER, 1)
        val nextName = if (siteName.isNotEmpty()) {
            "$siteName-$prefix-%03d.jpg".format(counter)
        } else {
            "$prefix-%03d.jpg".format(counter)
        }
        tvNextFileName.text = "다음 파일명: $nextName"
    }

    // 필요한 권한 확인 및 요청
    private fun checkAndRequestPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isEmpty()) {
            startPhotoService()
        } else {
            requestPermissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    // 백그라운드 서비스 시작
    private fun startPhotoService() {
        val intent = Intent(this, FloatingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "서비스 시작!\n사진 촬영 시 자동으로 이름이 변경됩니다.", Toast.LENGTH_LONG).show()
    }

    override fun onResume() {
        super.onResume()
        updateNextFileDisplay()
    }
}
