/* Copyright 2023 Tusky Contributors
 *
 * This file is a part of Pachli.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation; either version 3 of the
 * License, or (at your option) any later version.
 *
 * Pachli is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Tusky; if not,
 * see <http://www.gnu.org/licenses>. */

package app.pachli.components.trending.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pachli.appstore.EventHub
import app.pachli.appstore.PreferenceChangedEvent
import app.pachli.entity.Filter
import app.pachli.entity.TrendingTag
import app.pachli.entity.end
import app.pachli.entity.start
import app.pachli.network.MastodonApi
import app.pachli.viewdata.TrendingViewData
import at.connyduck.calladapter.networkresult.fold
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

class TrendingTagsViewModel @Inject constructor(
    private val mastodonApi: MastodonApi,
    private val eventHub: EventHub,
) : ViewModel() {
    enum class LoadingState {
        INITIAL, LOADING, REFRESHING, LOADED, ERROR_NETWORK, ERROR_OTHER
    }

    data class TrendingTagsUiState(
        val trendingViewData: List<TrendingViewData>,
        val loadingState: LoadingState,
    )

    val uiState: Flow<TrendingTagsUiState> get() = _uiState
    private val _uiState = MutableStateFlow(TrendingTagsUiState(listOf(), LoadingState.INITIAL))

    init {
        invalidate()

        // Collect PreferenceChangedEvent, FiltersActivity creates them when a filter is created
        // or deleted. Unfortunately, there's nothing in the event to determine if it's a filter
        // that was modified, so refresh on every preference change.
        viewModelScope.launch {
            eventHub.events
                .filterIsInstance<PreferenceChangedEvent>()
                .collect {
                    invalidate()
                }
        }
    }

    /**
     * Invalidate the current list of trending tags and fetch a new list.
     *
     * A tag is excluded if it is filtered by the user on their home timeline.
     */
    fun invalidate(refresh: Boolean = false) = viewModelScope.launch {
        if (refresh) {
            _uiState.value = TrendingTagsUiState(emptyList(), LoadingState.REFRESHING)
        } else {
            _uiState.value = TrendingTagsUiState(emptyList(), LoadingState.LOADING)
        }

        val deferredFilters = async { mastodonApi.getFilters() }

        mastodonApi.trendingTags().fold(
            { tagResponse ->

                val firstTag = tagResponse.firstOrNull()
                _uiState.value = if (firstTag == null) {
                    TrendingTagsUiState(emptyList(), LoadingState.LOADED)
                } else {
                    val homeFilters = deferredFilters.await().getOrNull()?.filter { filter ->
                        filter.context.contains(Filter.Kind.HOME.kind)
                    }
                    val tags = tagResponse
                        .filter { tag ->
                            homeFilters?.none { filter ->
                                filter.keywords.any { keyword -> keyword.keyword.equals(tag.name, ignoreCase = true) }
                            } ?: false
                        }
                        .sortedByDescending { tag -> tag.history.sumOf { it.uses.toLongOrNull() ?: 0 } }
                        .toTrendingViewDataTag()

                    val header = TrendingViewData.Header(firstTag.start(), firstTag.end())
                    TrendingTagsUiState(listOf(header) + tags, LoadingState.LOADED)
                }
            },
            { error ->
                Log.w(TAG, "failed loading trending tags", error)
                if (error is IOException) {
                    _uiState.value = TrendingTagsUiState(emptyList(), LoadingState.ERROR_NETWORK)
                } else {
                    _uiState.value = TrendingTagsUiState(emptyList(), LoadingState.ERROR_OTHER)
                }
            },
        )
    }

    private fun List<TrendingTag>.toTrendingViewDataTag(): List<TrendingViewData.Tag> {
        val maxTrendingValue = flatMap { tag -> tag.history }
            .mapNotNull { it.uses.toLongOrNull() }
            .maxOrNull() ?: 1

        return map { TrendingViewData.Tag.from(it, maxTrendingValue) }
    }

    companion object {
        private const val TAG = "TrendingViewModel"
    }
}