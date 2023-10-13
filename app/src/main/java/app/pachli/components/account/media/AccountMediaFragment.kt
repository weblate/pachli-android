/* Copyright 2022 Tusky Contributors
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

package app.pachli.components.account.media

import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.core.app.ActivityOptionsCompat
import androidx.core.view.MenuProvider
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.paging.LoadState
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.GridLayoutManager
import app.pachli.R
import app.pachli.ViewMediaActivity
import app.pachli.databinding.FragmentTimelineBinding
import app.pachli.db.AccountManager
import app.pachli.entity.Attachment
import app.pachli.interfaces.RefreshableFragment
import app.pachli.settings.PrefKeys
import app.pachli.util.hide
import app.pachli.util.openLink
import app.pachli.util.show
import app.pachli.util.viewBinding
import app.pachli.viewdata.AttachmentViewData
import com.google.android.material.color.MaterialColors
import com.mikepenz.iconics.IconicsDrawable
import com.mikepenz.iconics.typeface.library.googlematerial.GoogleMaterial
import com.mikepenz.iconics.utils.colorInt
import com.mikepenz.iconics.utils.sizeDp
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Fragment with multiple columns of media previews for the specified account.
 */
@AndroidEntryPoint
class AccountMediaFragment :
    Fragment(R.layout.fragment_timeline),
    RefreshableFragment,
    MenuProvider {

    @Inject
    lateinit var accountManager: AccountManager

    private val binding by viewBinding(FragmentTimelineBinding::bind)

    private val viewModel: AccountMediaViewModel by viewModels()

    private lateinit var adapter: AccountMediaGridAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.accountId = arguments?.getString(ACCOUNT_ID_ARG)!!
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)

        val preferences = PreferenceManager.getDefaultSharedPreferences(view.context)
        val useBlurhash = preferences.getBoolean(PrefKeys.USE_BLURHASH, true)

        adapter = AccountMediaGridAdapter(
            useBlurhash = useBlurhash,
            context = view.context,
            onAttachmentClickListener = ::onAttachmentClick,
        )

        val columnCount = view.context.resources.getInteger(R.integer.profile_media_column_count)
        val imageSpacing = view.context.resources.getDimensionPixelSize(R.dimen.profile_media_spacing)

        binding.recyclerView.addItemDecoration(GridSpacingItemDecoration(columnCount, imageSpacing, 0))

        binding.recyclerView.layoutManager = GridLayoutManager(view.context, columnCount)
        binding.recyclerView.adapter = adapter

        binding.swipeRefreshLayout.isEnabled = false
        binding.swipeRefreshLayout.setOnRefreshListener { refreshContent() }
        binding.swipeRefreshLayout.setColorSchemeColors(MaterialColors.getColor(binding.root, androidx.appcompat.R.attr.colorPrimary))

        binding.statusView.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.media.collectLatest { pagingData ->
                adapter.submitData(pagingData)
            }
        }

        adapter.addLoadStateListener { loadState ->
            binding.statusView.hide()
            binding.progressBar.hide()

            if (loadState.refresh != LoadState.Loading && loadState.source.refresh != LoadState.Loading) {
                binding.swipeRefreshLayout.isRefreshing = false
            }

            if (adapter.itemCount == 0) {
                when (loadState.refresh) {
                    is LoadState.NotLoading -> {
                        if (loadState.append is LoadState.NotLoading && loadState.source.refresh is LoadState.NotLoading) {
                            binding.statusView.show()
                            binding.statusView.setup(R.drawable.elephant_friend_empty, R.string.message_empty, null)
                        }
                    }
                    is LoadState.Error -> {
                        binding.statusView.show()
                        binding.statusView.setup((loadState.refresh as LoadState.Error).error)
                    }
                    is LoadState.Loading -> {
                        binding.progressBar.show()
                    }
                }
            }
        }
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.fragment_account_media, menu)
        menu.findItem(R.id.action_refresh)?.apply {
            icon = IconicsDrawable(requireContext(), GoogleMaterial.Icon.gmd_refresh).apply {
                sizeDp = 20
                colorInt = MaterialColors.getColor(binding.root, android.R.attr.textColorPrimary)
            }
        }
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        return when (menuItem.itemId) {
            R.id.action_refresh -> {
                binding.swipeRefreshLayout.isRefreshing = true
                refreshContent()
                true
            }
            else -> false
        }
    }

    private fun onAttachmentClick(selected: AttachmentViewData, view: View) {
        if (!selected.isRevealed) {
            viewModel.revealAttachment(selected)
            return
        }
        val attachmentsFromSameStatus = viewModel.attachmentData.filter { attachmentViewData ->
            attachmentViewData.statusId == selected.statusId
        }
        val currentIndex = attachmentsFromSameStatus.indexOf(selected)

        when (selected.attachment.type) {
            Attachment.Type.IMAGE,
            Attachment.Type.GIFV,
            Attachment.Type.VIDEO,
            Attachment.Type.AUDIO,
            -> {
                val intent = ViewMediaActivity.newIntent(context, attachmentsFromSameStatus, currentIndex)
                if (activity != null) {
                    val url = selected.attachment.url
                    ViewCompat.setTransitionName(view, url)
                    val options = ActivityOptionsCompat.makeSceneTransitionAnimation(requireActivity(), view, url)
                    startActivity(intent, options.toBundle())
                } else {
                    startActivity(intent)
                }
            }
            Attachment.Type.UNKNOWN -> {
                context?.openLink(selected.attachment.url)
            }
        }
    }

    override fun refreshContent() {
        adapter.refresh()
    }

    companion object {
        @Suppress("unused")
        private const val TAG = "AccountMediaFragment"

        fun newInstance(accountId: String): AccountMediaFragment {
            val fragment = AccountMediaFragment()
            val args = Bundle(1)
            args.putString(ACCOUNT_ID_ARG, accountId)
            fragment.arguments = args
            return fragment
        }

        private const val ACCOUNT_ID_ARG = "account_id"
    }
}
