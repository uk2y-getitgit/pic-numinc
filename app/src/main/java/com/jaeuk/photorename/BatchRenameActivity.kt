package com.jaeuk.photorename

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class BatchRenameActivity : AppCompatActivity() {

    private var batchFolderUri: Uri? = null
    private var sortedFiles: List<DocumentFile> = emptyList()

    private lateinit var tvBatchFolder: TextView
    private lateinit var tvFileCount: TextView
    private lateinit var tvDateRange: TextView
    private lateinit var llFileList: LinearLayout
    private lateinit var cardFileList: View
    private lateinit var etBatchSiteName: EditText
    private lateinit var etBatchPrefix: EditText
    private lateinit var etStartNumber: EditText
    private lateinit var llPreviewList: LinearLayout
    private lateinit var cardPreview: View
    private lateinit var btnConvert: Button

    // 폴더 선택 런처 — DCIM 초기 위치로 갤러리(파일탐색기) 열기
    private val batchFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri ?: return@registerForActivityResult
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        batchFolderUri = uri
        loadFilesFromFolder(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_batch_rename)

        // View 연결
        tvBatchFolder   = findViewById(R.id.tvBatchFolder)
        tvFileCount     = findViewById(R.id.tvFileCount)
        tvDateRange     = findViewById(R.id.tvDateRange)
        llFileList      = findViewById(R.id.llFileList)
        cardFileList    = findViewById(R.id.cardFileList)
        etBatchSiteName = findViewById(R.id.etBatchSiteName)
        etBatchPrefix   = findViewById(R.id.etBatchPrefix)
        etStartNumber   = findViewById(R.id.etStartNumber)
        llPreviewList   = findViewById(R.id.llPreviewList)
        cardPreview     = findViewById(R.id.cardPreview)
        btnConvert      = findViewById(R.id.btnConvert)

        // 뒤로가기
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        // 폴더 선택 — DCIM 기본 위치로 시스템 파일탐색기(갤러리) 열기
        findViewById<View>(R.id.btnSelectBatchFolder).setOnClickListener {
            val dcimUri = Uri.parse(
                "content://com.android.externalstorage.documents/tree/primary%3ADCIM"
            )
            batchFolderLauncher.launch(dcimUri)
        }

        // X 버튼
        findViewById<View>(R.id.btnClearBatchSite).setOnClickListener {
            etBatchSiteName.text.clear(); etBatchSiteName.requestFocus()
        }
        findViewById<View>(R.id.btnClearBatchPrefix).setOnClickListener {
            etBatchPrefix.text.clear(); etBatchPrefix.requestFocus()
        }

        // 입력 변경 시 미리보기 갱신
        val watcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = updateBatchPreview()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }
        etBatchSiteName.addTextChangedListener(watcher)
        etBatchPrefix.addTextChangedListener(watcher)
        etStartNumber.addTextChangedListener(watcher)

        // 일괄 변환 버튼
        btnConvert.setOnClickListener { confirmAndBatchRename() }

        // 하단 탭 — 홈으로 이동
        findViewById<View>(R.id.navTabHome).setOnClickListener {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        }
    }

    // ── 폴더에서 이미지 파일 로드 (오래된 순 정렬) ──────────────────────────
    private fun loadFilesFromFolder(uri: Uri) {
        tvBatchFolder.text = "불러오는 중..."
        tvBatchFolder.setTextColor(getColor(R.color.text_dim))
        cardFileList.visibility = View.GONE
        cardPreview.visibility  = View.GONE
        btnConvert.isEnabled    = false

        CoroutineScope(Dispatchers.Main).launch {
            val (files, displayPath) = withContext(Dispatchers.IO) {
                val doc = DocumentFile.fromTreeUri(applicationContext, uri)
                val path = doc?.uri?.path
                    ?.replace("/tree/primary:", "/내장 저장공간/")
                    ?.replace(":", "/")
                    ?: uri.toString()

                val imageFiles = doc?.listFiles()
                    ?.filter { it.isFile && isImageFile(it.name) }
                    ?.sortedBy { it.lastModified() }   // 오래된 순 → 001번
                    ?: emptyList()

                Pair(imageFiles, path)
            }

            sortedFiles = files

            tvBatchFolder.text = displayPath
            tvBatchFolder.setTextColor(getColor(R.color.orange_lt))

            if (files.isEmpty()) {
                Toast.makeText(
                    this@BatchRenameActivity,
                    "선택한 폴더에 이미지 파일(.jpg/.jpeg/.png)이 없습니다.",
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            // 파일 수 / 날짜 범위
            tvFileCount.text = "발견된 이미지: ${files.size}개"
            val sdf = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.getDefault())
            tvDateRange.text =
                "기간: ${sdf.format(Date(files.first().lastModified()))}  →  " +
                sdf.format(Date(files.last().lastModified()))

            // 파일명 목록 (최대 20개)
            llFileList.removeAllViews()
            val showCount = minOf(files.size, 20)
            files.take(showCount).forEachIndexed { idx, file ->
                addFileRow(llFileList, "%2d.  %s".format(idx + 1, file.name ?: "(알 수 없음)"),
                    getColor(R.color.text_dim))
            }
            if (files.size > showCount) {
                addFileRow(llFileList, "   ⋯ 외 ${files.size - showCount}개 더 있음",
                    getColor(R.color.text_dim))
            }

            cardFileList.visibility = View.VISIBLE
            updateBatchPreview()
        }
    }

    private fun isImageFile(name: String?): Boolean {
        val lower = name?.lowercase() ?: return false
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
    }

    private fun addFileRow(parent: LinearLayout, text: String, color: Int) {
        parent.addView(TextView(this).apply {
            this.text = text
            textSize = 11f
            setTextColor(color)
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, 3, 0, 3)
        })
    }

    // ── 변환 결과 미리보기 (처음 5개) ────────────────────────────────────────
    private fun updateBatchPreview() {
        if (sortedFiles.isEmpty()) return

        val site     = etBatchSiteName.text.toString().trim()
        val prefix   = etBatchPrefix.text.toString().trim().ifEmpty { "img" }
        val startNum = etStartNumber.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 1

        llPreviewList.removeAllViews()
        val previewCount = minOf(sortedFiles.size, 5)

        sortedFiles.take(previewCount).forEachIndexed { idx, file ->
            val newName = buildBatchFileName(site, prefix, startNum + idx)
            val orig    = file.name ?: "(알 수 없음)"
            addFileRow(llPreviewList,
                "$orig\n  →  $newName",
                getColor(R.color.orange_lt))
        }
        if (sortedFiles.size > previewCount) {
            addFileRow(llPreviewList,
                "   ⋯ 외 ${sortedFiles.size - previewCount}개",
                getColor(R.color.text_dim))
        }

        cardPreview.visibility = View.VISIBLE
        btnConvert.isEnabled   = true
    }

    private fun buildBatchFileName(siteName: String, prefix: String, number: Int): String {
        val num        = "%03d".format(number)
        val safeSite   = siteName.replace(Regex("""[/\\:*?"<>|\u0000]"""), "_")
        val safePrefix = prefix.replace(Regex("""[/\\:*?"<>|\u0000]"""), "_").ifEmpty { "img" }
        return if (safeSite.isNotEmpty()) "$safeSite-$safePrefix-$num.jpg"
               else "$safePrefix-$num.jpg"
    }

    // ── 변환 확인 다이얼로그 ──────────────────────────────────────────────────
    private fun confirmAndBatchRename() {
        val uri = batchFolderUri ?: run {
            Toast.makeText(this, "변환할 폴더를 먼저 선택해주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        if (sortedFiles.isEmpty()) {
            Toast.makeText(this, "이미지 파일이 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }

        val site     = etBatchSiteName.text.toString().trim()
        val prefix   = etBatchPrefix.text.toString().trim().ifEmpty { "img" }
        val startNum = etStartNumber.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 1
        val endNum   = startNum + sortedFiles.size - 1

        val firstFile = buildBatchFileName(site, prefix, startNum)
        val lastFile  = buildBatchFileName(site, prefix, endNum)

        AlertDialog.Builder(this)
            .setTitle("⚠️ 일괄 변환 확인")
            .setMessage(
                "총 ${sortedFiles.size}개 파일 이름을 변경합니다.\n\n" +
                "첫 번째:  $firstFile\n" +
                "마지막:  $lastFile\n\n" +
                "오래된 사진 → 001번부터 순서대로 적용됩니다.\n" +
                "번호 순서는 변경되지 않습니다.\n\n" +
                "⚠️  이 작업은 되돌릴 수 없습니다."
            )
            .setPositiveButton("변환 시작") { _, _ ->
                CoroutineScope(Dispatchers.Main).launch {
                    performBatchRename(uri, site, prefix, startNum)
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ── 실제 일괄 이름변경 실행 ───────────────────────────────────────────────
    private suspend fun performBatchRename(
        folderUri: Uri,
        siteName: String,
        prefix: String,
        startNum: Int
    ) {
        btnConvert.isEnabled = false
        btnConvert.text = "변환 중..."

        val result = withContext(Dispatchers.IO) {
            var successCount = 0
            var failCount    = 0

            // 스냅샷 고정 후 처리 (이동 중 목록 변경 방지)
            val snapshot = sortedFiles.toList()

            snapshot.forEachIndexed { idx, file ->
                val num     = startNum + idx
                val newName = buildBatchFileName(siteName, prefix, num)

                try {
                    val treeDocId = DocumentsContract.getTreeDocumentId(folderUri)
                    val fileDocId = "$treeDocId/${file.name}"
                    val docUri    = DocumentsContract.buildDocumentUriUsingTree(folderUri, fileDocId)

                    val renamed = DocumentsContract.renameDocument(contentResolver, docUri, newName)
                    if (renamed != null) successCount++ else failCount++
                } catch (e: Exception) {
                    e.printStackTrace()
                    failCount++
                }
            }

            buildString {
                append("✅ ${successCount}개 변환 완료")
                if (failCount > 0) append("\n⚠️ ${failCount}개 실패")
            }
        }

        btnConvert.text      = "📋  일괄 변환 시작"
        btnConvert.isEnabled = true

        AlertDialog.Builder(this)
            .setTitle("변환 결과")
            .setMessage(result)
            .setPositiveButton("확인") { _, _ ->
                batchFolderUri?.let { loadFilesFromFolder(it) }
            }
            .show()
    }
}
