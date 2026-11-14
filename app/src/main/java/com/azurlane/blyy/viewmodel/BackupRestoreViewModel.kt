package com.azurlane.blyy.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azurlane.blyy.data.backup.BackupFileInfo
import com.azurlane.blyy.data.backup.BackupManager
import com.azurlane.blyy.data.backup.BackupPayload
import com.azurlane.blyy.data.local.PlayerSettingsDataStore
import com.azurlane.blyy.util.diagnostics.AppLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 备份 / 恢复页状态。
 *
 * [pendingImport] 非空时 UI 弹确认框：**恢复是覆盖型不可逆操作**，
 * 必须先让用户看到"这份备份是什么时候、来自哪个版本、里面有多少东西"再确认。
 */
data class BackupRestoreUiState(
    val loading: Boolean = true,
    val busy: Boolean = false,
    val autoBackupEnabled: Boolean = true,
    val lastBackupAt: Long = 0L,
    val lastRestoreAt: Long = 0L,
    val localBackups: List<BackupFileInfo> = emptyList(),
    val pendingImport: BackupPayload? = null,
    val message: String? = null,
    val messageIsError: Boolean = false
)

@HiltViewModel
class BackupRestoreViewModel @Inject constructor(
    private val backupManager: BackupManager,
    private val settings: PlayerSettingsDataStore
) : ViewModel() {

    private companion object {
        private const val TAG = "BackupRestoreVM"
    }

    private val _uiState = MutableStateFlow(BackupRestoreUiState())
    val uiState: StateFlow<BackupRestoreUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val backups = runCatching { backupManager.listLocalBackups() }.getOrDefault(emptyList())
            _uiState.update {
                it.copy(
                    loading = false,
                    autoBackupEnabled = settings.autoBackupEnabled.first(),
                    lastBackupAt = settings.lastBackupAt.first(),
                    lastRestoreAt = settings.lastRestoreAt.first(),
                    localBackups = backups
                )
            }
        }
    }

    fun setAutoBackupEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settings.setAutoBackupEnabled(enabled)
            _uiState.update { it.copy(autoBackupEnabled = enabled) }
            toast(if (enabled) "已开启自动备份（每日首次启动时执行）" else "已关闭自动备份")
        }
    }

    /** 立即备份到用户指定的位置（SAF） */
    fun exportTo(uri: Uri) {
        runBusy {
            backupManager.exportTo(uri)
                .onSuccess { bytes ->
                    toast("备份完成（${bytes / 1024} KB）")
                    refreshAfterMutation()
                }
                .onFailure { error("备份失败：${it.message ?: "未知错误"}") }
        }
    }

    /** 备份到应用私有目录（可被自动备份滚动清理，但换机前可先分享出去） */
    fun backupToLocal() {
        runBusy {
            backupManager.exportToLocalFile()
                .onSuccess { file ->
                    toast("已保存到本机：${file.name}（${file.length() / 1024} KB）")
                    refreshAfterMutation()
                }
                .onFailure { error("备份失败：${it.message ?: "未知错误"}") }
        }
    }

    /** 选择文件后先解析并进入预览确认，不直接恢复 */
    fun loadImportPreview(uri: Uri) {
        runBusy {
            backupManager.readFromUri(uri)
                .onSuccess { payload -> _uiState.update { it.copy(pendingImport = payload) } }
                .onFailure { error("无法读取备份：${it.message ?: "未知错误"}") }
        }
    }

    fun dismissImport() {
        _uiState.update { it.copy(pendingImport = null) }
    }

    /** 确认执行恢复 */
    fun confirmImport() {
        val payload = _uiState.value.pendingImport ?: return
        runBusy {
            backupManager.restore(payload)
                .onSuccess { result ->
                    _uiState.update { it.copy(pendingImport = null) }
                    toast("恢复完成：${result.summary()}")
                    AppLog.i(TAG, "restore result: $result")
                    refreshAfterMutation()
                }
                .onFailure { error("恢复失败：${it.message ?: "未知错误"}") }
        }
    }

    /** 从本机备份文件恢复（同样走预览确认） */
    fun loadLocalPreview(info: BackupFileInfo) {
        runBusy {
            backupManager.readFromFile(info.file)
                .onSuccess { payload -> _uiState.update { it.copy(pendingImport = payload) } }
                .onFailure { error("备份文件已损坏：${it.message ?: "无法解析"}") }
        }
    }

    fun deleteLocal(info: BackupFileInfo) {
        runBusy {
            val deleted = backupManager.deleteLocalBackup(info.file)
            toast(if (deleted) "已删除该备份" else "删除失败：文件不在备份目录内")
            refreshAfterMutation()
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null, messageIsError = false) }
    }

    // ── 内部工具 ──

    private fun runBusy(block: suspend () -> Unit) {
        if (_uiState.value.busy) return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            try {
                block()
            } finally {
                _uiState.update { it.copy(busy = false) }
            }
        }
    }

    private fun refreshAfterMutation() {
        viewModelScope.launch {
            val backups = runCatching { backupManager.listLocalBackups() }.getOrDefault(emptyList())
            _uiState.update {
                it.copy(
                    lastBackupAt = settings.lastBackupAt.first(),
                    lastRestoreAt = settings.lastRestoreAt.first(),
                    localBackups = backups
                )
            }
        }
    }

    private fun toast(text: String) {
        _uiState.update { it.copy(message = text, messageIsError = false) }
    }

    private fun error(text: String) {
        AppLog.w(TAG, text)
        _uiState.update { it.copy(message = text, messageIsError = true) }
    }
}
