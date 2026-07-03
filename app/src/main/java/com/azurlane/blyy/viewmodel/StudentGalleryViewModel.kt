package com.azurlane.blyy.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azurlane.blyy.data.model.StudentGallery
import com.azurlane.blyy.data.repository.ShipRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StudentGalleryState(
    val isLoading: Boolean = false,
    val gallery: StudentGallery? = null,
    val error: String? = null,
    val selectedTabIndex: Int = 0
)

@HiltViewModel
class StudentGalleryViewModel @Inject constructor(
    private val shipRepository: ShipRepository
) : ViewModel() {

    private val _state = MutableStateFlow(StudentGalleryState())
    val state: StateFlow<StudentGalleryState> = _state.asStateFlow()

    fun loadGallery(studentLink: String) {
        viewModelScope.launch {
            _state.value = StudentGalleryState(isLoading = true)
            try {
                val gallery = shipRepository.fetchStudentGallery(studentLink)
                // 自动选择第一个有内容的 tab
                val firstNonEmptyIndex = gallery.tabs.indexOfFirst { !it.isEmpty }
                _state.value = StudentGalleryState(
                    isLoading = false,
                    gallery = gallery,
                    selectedTabIndex = if (firstNonEmptyIndex >= 0) firstNonEmptyIndex else 0
                )
            } catch (e: Exception) {
                _state.value = StudentGalleryState(
                    isLoading = false,
                    error = e.message ?: "加载学生立绘失败"
                )
            }
        }
    }

    fun selectTab(index: Int) {
        _state.value = _state.value.copy(selectedTabIndex = index)
    }
}
