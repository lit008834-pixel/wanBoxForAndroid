// @author 雾晚
package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayoutMediator
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.databinding.LayoutToolsBinding

class ToolsFragment : ToolbarFragment(R.layout.layout_tools) {

    companion object {
        private const val ARG_INITIAL_TAB = "initial_tab"
        private const val CUSTOM_ICON_TAB = 2

        fun forCustomIcon() = ToolsFragment().apply {
            arguments = Bundle().apply { putInt(ARG_INITIAL_TAB, CUSTOM_ICON_TAB) }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar.setTitle(R.string.menu_tools)

        val tools = mutableListOf<NamedFragment>()
        tools.add(NetworkFragment())
        tools.add(BackupFragment())
        tools.add(CustomIconFragment())

        val binding = LayoutToolsBinding.bind(view)
        binding.toolsPager.adapter = ToolsAdapter(tools)

        TabLayoutMediator(binding.toolsTab, binding.toolsPager) { tab, position ->
            tab.text = tools[position].name()
            tab.view.setOnLongClickListener { // clear toast
                true
            }
        }.attach()
        if (savedInstanceState == null) {
            binding.toolsPager.setCurrentItem(
                arguments?.getInt(ARG_INITIAL_TAB)?.coerceIn(0, tools.lastIndex) ?: 0,
                false
            )
        }
    }

    inner class ToolsAdapter(val tools: List<Fragment>) : FragmentStateAdapter(this) {

        override fun getItemCount() = tools.size

        override fun createFragment(position: Int) = tools[position]
    }

}
