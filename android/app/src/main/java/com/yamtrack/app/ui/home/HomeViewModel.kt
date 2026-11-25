package com.yamtrack.app.ui.home

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yamtrack.app.data.model.MediaItem
import com.yamtrack.app.data.model.MediaStatus
import com.yamtrack.app.data.model.MediaType
import com.yamtrack.app.data.model.Result
import com.yamtrack.app.data.repository.YamtrackRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: YamtrackRepository
) : ViewModel() {

    /**
     * Recently-updated items grouped by media type. Insertion order is the
     * canonical `MediaType.parentTypes` order so the home page renders the
     * same section order regardless of how the API returned the items.
     */
    private val _recentByType = MutableLiveData<Map<MediaType, List<MediaItem>>>(emptyMap())
    val recentByType: LiveData<Map<MediaType, List<MediaItem>>> = _recentByType

    private val _planningByType = MutableLiveData<Map<MediaType, List<MediaItem>>>(emptyMap())
    val planningByType: LiveData<Map<MediaType, List<MediaItem>>> = _planningByType

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    /**
     * Why the last load failed, or null. On failure the previous lists are
     * kept, so a brief server hiccup doesn't blank the screen.
     */
    private val _error = MutableLiveData<String?>(null)
    val error: LiveData<String?> = _error

    fun loadData() {
        viewModelScope.launch {
            _isLoading.value = true

            var (recent, planning) = fetch()
            // A server that's restarting, or a proxy waking it, can fail a
            // request briefly: try once more before reporting it.
            if (recent.isTransientError() || planning.isTransientError()) {
                delay(RETRY_DELAY_MS)
                val again = fetch()
                recent = again.first
                planning = again.second
            }

            val failure = listOf(recent, planning).filterIsInstance<Result.Error>().firstOrNull()
            if (recent is Result.Success) _recentByType.value = groupByType(recent)
            if (planning is Result.Success) _planningByType.value = groupByType(planning)
            _error.value = failure?.message

            _isLoading.value = false
        }
    }

    private suspend fun fetch(): Pair<Result<List<MediaItem>>, Result<List<MediaItem>>> = coroutineScope {
        val recent = async { repository.getAllMedia(sort = "updated_desc", limit = 200) }
        val planning = async {
            repository.getAllMedia(status = MediaStatus.PLANNING, sort = "updated_desc", limit = 200)
        }
        recent.await() to planning.await()
    }

    /** No response, a gateway/server error, or a 404 from a proxy in front. */
    private fun Result<*>.isTransientError(): Boolean =
        this is Result.Error && (code == null || code == 404 || code >= 500)

    /** Group by parent media type, preserve API ordering inside each group. */
    private fun groupByType(result: Result<List<MediaItem>>): Map<MediaType, List<MediaItem>> {
        val items = (result as? Result.Success)?.data ?: return emptyMap()
        val groups = LinkedHashMap<MediaType, MutableList<MediaItem>>()
        MediaType.parentTypes.forEach { groups[it] = mutableListOf() }
        items.forEach { item ->
            val type = item.mediaType ?: return@forEach
            groups[type]?.add(item)
        }
        return groups.filterValues { it.isNotEmpty() }
    }

    private companion object {
        const val RETRY_DELAY_MS = 1500L
    }
}
