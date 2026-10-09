package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.databinding.ActivityNodeSelectDialogBinding
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NodeSelectDialogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNodeSelectDialogBinding
    private val groups = mutableListOf<ProxyGroup>()
    private val displayedNodes = mutableListOf<ProxyEntity>()
    private lateinit var adapter: NodeAdapter
    private var currentGroupId: Long = -1L
    // @author 雾晚: three-state selection (idle -> selecting -> confirmed/failed).
    // The checkmark follows the actually running profile, not the saved intent.
    private var selectingId: Long = 0L
    private var filterRevision = 0
    private val dialogScope = MainScope()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNodeSelectDialogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.dialogBackdrop.setOnClickListener { finishWithFade() }
        binding.dialogCard.setOnClickListener { /* prevent dismissal on clicking card */ }
        binding.btnCloseDialog.setOnClickListener { finishWithFade() }

        adapter = NodeAdapter { selectedProxy ->
            selectNode(selectedProxy)
        }
        binding.nodeRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.nodeRecyclerView.adapter = adapter

        loadGroupsAndNodes()
    }

    private fun loadGroupsAndNodes() {
        runOnDefaultDispatcher {
            val allGroups = SagerDatabase.groupDao.allGroups().filter { !it.ungrouped && !DataStore.isGroupDisabled(it.id) || it.ungrouped }
            val initialNodes = SagerDatabase.proxyDao.getAll().filter { !DataStore.isGroupDisabled(it.groupId) }

            onMainDispatcher {
                groups.clear()
                groups.addAll(allGroups)

                binding.groupTabLayout.removeAllTabs()
                binding.groupTabLayout.addTab(binding.groupTabLayout.newTab().setText(R.string.group_all))
                for (group in groups) {
                    binding.groupTabLayout.addTab(binding.groupTabLayout.newTab().setText(group.displayName()))
                }

                binding.groupTabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                    override fun onTabSelected(tab: TabLayout.Tab?) {
                        val pos = tab?.position ?: 0
                        currentGroupId = if (pos == 0) -1L else groups.getOrNull(pos - 1)?.id ?: -1L
                        filterNodes()
                    }
                    override fun onTabUnselected(tab: TabLayout.Tab?) {}
                    override fun onTabReselected(tab: TabLayout.Tab?) {}
                })

                updateNodeList(initialNodes)
            }
        }
    }

    private fun filterNodes() {
        val revision = ++filterRevision
        runOnDefaultDispatcher {
            val nodes = if (currentGroupId == -1L) {
                SagerDatabase.proxyDao.getAll().filter { !DataStore.isGroupDisabled(it.groupId) }
            } else {
                if (DataStore.isGroupDisabled(currentGroupId)) emptyList() else SagerDatabase.proxyDao.getByGroup(currentGroupId)
            }
            onMainDispatcher {
                // @author 雾晚: drop stale filter results; only the latest tab wins.
                if (revision == filterRevision) updateNodeList(nodes)
            }
        }
    }

    private fun updateNodeList(nodes: List<ProxyEntity>) {
        displayedNodes.clear()
        displayedNodes.addAll(nodes)
        adapter.notifyDataSetChanged()

        if (displayedNodes.isEmpty()) {
            binding.emptyHint.visibility = View.VISIBLE
            binding.nodeRecyclerView.visibility = View.GONE
        } else {
            binding.emptyHint.visibility = View.GONE
            binding.nodeRecyclerView.visibility = View.VISIBLE
        }
    }

    private fun selectNode(proxy: ProxyEntity) {
        // @author 雾晚: three-state selection. Show "selecting", wait for the module
        // observer to confirm the actually running profile, then close. Slow full
        // reloads close with a hint instead of blocking the dialog.
        if (selectingId != 0L) return
        selectingId = proxy.id
        adapter.notifyDataSetChanged()
        SagerNet.selectProfile(proxy.id)
        dialogScope.launch {
            val confirmed = withContext(Dispatchers.IO) {
                repeat(5) {
                    delay(if (it == 0) 800L else 1000L)
                    val status = runCatching { io.nekohasekai.sagernet.bg.RootModuleClient.call("status") }.getOrNull()
                    if (status != null && status.connected && status.profileId == proxy.id) return@withContext true
                }
                false
            }
            selectingId = 0L
            if (confirmed) {
                finishWithFade()
            } else {
                adapter.notifyDataSetChanged()
                android.widget.Toast.makeText(
                    this@NodeSelectDialogActivity,
                    getString(R.string.node_switch_in_progress),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                finishWithFade()
            }
        }
    }

    override fun onDestroy() {
        dialogScope.cancel()
        super.onDestroy()
    }

    private fun finishWithFade() {
        finish()
        overridePendingTransition(0, android.R.anim.fade_out)
    }

    inner class NodeAdapter(
        private val onClick: (ProxyEntity) -> Unit
    ) : RecyclerView.Adapter<NodeAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val root: View = view.findViewById(R.id.node_item_root)
            val name: TextView = view.findViewById(R.id.node_name)
            val type: TextView = view.findViewById(R.id.node_type)
            val ping: TextView = view.findViewById(R.id.node_ping)
            val checkIcon: ImageView = view.findViewById(R.id.node_check_icon)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_dialog_node, parent, false)
            return VH(view)
        }

        @SuppressLint("SetTextI18n")
        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = displayedNodes[position]
            // @author 雾晚: checkmark follows the actually running profile
            // (DataStore.currentProfile, updated by the module observer),
            // not the saved selection intent.
            val runningId = DataStore.currentProfile.takeIf { it > 0 } ?: DataStore.selectedProxy
            val isSelected = item.id == runningId
            val isSelecting = item.id == selectingId

            holder.name.text = item.displayName()
            holder.type.text = item.displayType()

            if (item.ping > 0) {
                val color = when {
                    item.ping < 150 -> "#10B981"
                    item.ping < 350 -> "#F59E0B"
                    else -> "#EF4444"
                }
                holder.ping.text = "${item.ping}ms 🟢"
                holder.ping.setTextColor(Color.parseColor(color))
            } else {
                holder.ping.text = "— ⚪"
                holder.ping.setTextColor(Color.parseColor("#94A3B8"))
            }

            if (isSelecting) {
                holder.root.setBackgroundResource(R.drawable.bg_item_node_selected)
                holder.checkIcon.visibility = View.VISIBLE
                holder.ping.text = "…"
            } else if (isSelected) {
                holder.root.setBackgroundResource(R.drawable.bg_item_node_selected)
                holder.checkIcon.visibility = View.VISIBLE
            } else {
                holder.root.setBackgroundResource(R.drawable.bg_item_node_normal)
                holder.checkIcon.visibility = View.GONE
            }

            holder.root.setOnClickListener {
                onClick(item)
            }
        }

        override fun getItemCount() = displayedNodes.size
    }
}
