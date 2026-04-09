package com.jaeuk.photorename

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
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
    private lateinit var tvCurrentCounter: TextView
    private lateinit var tvPreview: TextView
    private lateinit var tvSelectedFolder: TextView
    private lateinit var tvServiceStatus: TextView
    private lateinit var chipSite: TextView
    private lateinit var chipPre: TextView
    private lateinit var chipNum: TextView

    private lateinit var btnStartService: Button
    private lateinit var btnStopService: Button
    private lateinit var btnResetCounter: Button
    private lateinit var btnSelectFolder: Button
    private lateinit var btnClearSite: Button
    private lateinit var btnClearPrefix: Button

    companion object {
        const val PREFS_NAME = "PhotoRenamePrefs"
        const val KEY_PREFIX = "prefix"
        const val KEY_SITE_NAME = "site_name"
        const val KEY_COUNTER = "counter"
        const val KEY_FOLDER_URI = "folder_uri"
        const val DEFAULT_PREFIX = "img"
    }

    // 권한 요청 결과 처리
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            startPhotoService()
        } else {
            Toast.makeText(this, "사진 접근 권한이 필요합니다.\n설정 > 앱 > 권한에서 허용해주세요.", Toast.LENGTH_LONG).show()
        }
    }

    // 폴더 선택 결과 처리
    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        // 앱 재시작 후에도 권한 유지
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        prefs.edit().putString(KEY_FOLDER_URI, uri.toString()).apply()
        updateFolderDisplay(uri)
        Toast.makeText(this, "폴더 선택 완료!", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        // UI 요소 연결
        etPrefix         = findViewById(R.id.etPrefix)
        etSiteName       = findViewById(R.id.etSiteName)
        tvCurrentCounter = findViewById(R.id.tvCurrentCounter)
        tvPreview        = findViewById(R.id.tvPreview)
        tvSelectedFolder = findViewById(R.id.tvSelectedFolder)
        tvServiceStatus  = findViewById(R.id.tvServiceStatus)
        chipSite         = findViewById(R.id.chipSite)
        chipPre          = findViewById(R.id.chipPre)
        chipNum          = findViewById(R.id.chipNum)
        btnStartService  = findViewById(R.id.btnStartService)
        btnStopService   = findViewById(R.id.btnStopService)
        btnResetCounter  = findViewById(R.id.btnResetCounter)
        btnSelectFolder  = findViewById(R.id.btnSelectFolder)
        btnClearSite     = findViewById(R.id.btnClearSite)
        btnClearPrefix   = findViewById(R.id.btnClearPrefix)

        // 저장된 값 불러오기
        etPrefix.setText(prefs.getString(KEY_PREFIX, DEFAULT_PREFIX))
        etSiteName.setText(prefs.getString(KEY_SITE_NAME, ""))
        prefs.getString(KEY_FOLDER_URI, null)?.let { updateFolderDisplay(Uri.parse(it)) }
        updateCounterDisplay()
        updatePreview()

        // 입력 변경 시 미리보기 실시간 반영
        val watcher = object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = updatePreview()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }
        etPrefix.addTextChangedListener(watcher)
        etSiteName.addTextChangedListener(watcher)

        // X 버튼
        btnClearSite.setOnClickListener { etSiteName.text.clear(); etSiteName.requestFocus() }
        btnClearPrefix.setOnClickListener { etPrefix.text.clear(); etPrefix.requestFocus() }

        // 폴더 선택 — DCIM 폴더를 초기 위치로 열기
        btnSelectFolder.setOnClickListener {
            val initialUri = Uri.parse(
                "content://com.android.externalstorage.documents/tree/primary%3ADCIM"
            )
            folderPickerLauncher.launch(initialUri)
        }

        // 서비스 시작 — 폴더 미선택 시 차단
        btnStartService.setOnClickListener {
            if (prefs.getString(KEY_FOLDER_URI, null) == null) {
                Toast.makeText(this, "📁 먼저 감시 폴더를 선택해주세요.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            saveSettings()
            checkAndRequestPermissions()
        }

        // 서비스 중지
        btnStopService.setOnClickListener {
            stopService(Intent(this, FloatingService::class.java))
            setServiceStatus(running = false)
            Toast.makeText(this, "서비스 중지 — 자동 이름 변경이 꺼졌습니다.", Toast.LENGTH_SHORT).show()
        }

        // 번호 초기화 — 현재 입력값 기준 미리보기 포함
        btnResetCounter.setOnClickListener {
            val site   = etSiteName.text.toString().trim()
            val prefix = etPrefix.text.toString().trim().ifEmpty { DEFAULT_PREFIX }
            val previewName = if (site.isNotEmpty()) "$site-$prefix-001.jpg" else "$prefix-001.jpg"

            AlertDialog.Builder(this)
                .setTitle("번호 초기화")
                .setMessage("현재 입력된 현장명으로 001부터 시작합니다.\n\n첫 번째 파일명: $previewName\n\n서비스 실행 중이면 즉시 적용됩니다.")
                .setPositiveButton("초기화") { _, _ ->
                    saveSettings()
                    prefs.edit().putInt(KEY_COUNTER, 1).apply()
                    updateCounterDisplay()
                    updatePreview()
                    Toast.makeText(this, "✅ 초기화 완료: $previewName 부터 시작", Toast.LENGTH_LONG).show()
                }
                .setNegativeButton("취소", null)
                .show()
        }
    }

    // 서비스 상태 뱃지 업데이트
    private fun setServiceStatus(running: Boolean) {
        if (running) {
            tvServiceStatus.text = "● 서비스 중"
            tvServiceStatus.setTextColor(getColor(R.color.green))
            tvServiceStatus.background = getDrawable(R.drawable.bg_status_active)
        } else {
            tvServiceStatus.text = "● 대기 중"
            tvServiceStatus.setTextColor(getColor(R.color.text_dim))
            tvServiceStatus.background = getDrawable(R.drawable.bg_status_inactive)
        }
    }

    // 선택된 폴더 경로 표시 (DocumentFile 사용 — 더 정확한 경로)
    private fun updateFolderDisplay(uri: Uri) {
        val doc = DocumentFile.fromTreeUri(this, uri)
        val displayPath = doc?.uri?.path
            ?.replace("/tree/primary:", "/내장 저장공간/")
            ?.replace(":", "/")
            ?: uri.toString()
        tvSelectedFolder.text = displayPath
        tvSelectedFolder.setTextColor(getColor(R.color.orange_lt))
    }

    private fun saveSettings() {
        prefs.edit()
            .putString(KEY_PREFIX, etPrefix.text.toString().trim().ifEmpty { DEFAULT_PREFIX })
            .putString(KEY_SITE_NAME, etSiteName.text.toString().trim())
            .apply()
    }

    // 카운터 번호 표시 (숫자만)
    private fun updateCounterDisplay() {
        val counter = prefs.getInt(KEY_COUNTER, 1)
        tvCurrentCounter.text = "%03d".format(counter)
    }

    // 파일명 미리보기 + 칩 업데이트
    private fun updatePreview() {
        val prefix  = etPrefix.text.toString().trim().ifEmpty { DEFAULT_PREFIX }
        val site    = etSiteName.text.toString().trim()
        val counter = prefs.getInt(KEY_COUNTER, 1)
        val numStr  = "%03d".format(counter)

        tvPreview.text = if (site.isNotEmpty()) "$site-$prefix-$numStr.jpg" else "$prefix-$numStr.jpg"

        if (site.isNotEmpty()) {
            chipSite.text = site
            chipSite.visibility = View.VISIBLE
        } else {
            chipSite.visibility = View.GONE
        }
        chipPre.text = prefix
        chipNum.text = numStr
    }

    private fun checkAndRequestPermissions() {
        val permissions = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.P ->
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            else ->
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isEmpty()) startPhotoService()
        else requestPermissionLauncher.launch(notGranted.toTypedArray())
    }

    private fun startPhotoService() {
        val intent = Intent(this, FloatingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
        setServiceStatus(running = true)
        Toast.makeText(this, "서비스 시작!\n사진 촬영 시 자동으로 이름이 변경됩니다.", Toast.LENGTH_LONG).show()
    }

    override fun onResume() {
        super.onResume()
        updateCounterDisplay()
        updatePreview()
    }
}
