package com.cloudvault.app

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DuplicatesDialog {

    fun show(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return

        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_duplicates, null)
        val tvDuplicatesSummary: TextView = view.findViewById(R.id.tvDuplicatesSummary)
        val btnCloseDuplicates: TextView = view.findViewById(R.id.btnCloseDuplicates)
        val btnKeepOldest: MaterialButton = view.findViewById(R.id.btnKeepOldest)
        val btnKeepNewest: MaterialButton = view.findViewById(R.id.btnKeepNewest)
        val btnClearDuplicatesSelection: MaterialButton = view.findViewById(R.id.btnClearDuplicatesSelection)
        val rvDuplicates: RecyclerView = view.findViewById(R.id.rvDuplicates)
        val layoutEmptyDuplicates: LinearLayout = view.findViewById(R.id.layoutEmptyDuplicates)
        val btnDeleteDuplicates: MaterialButton = view.findViewById(R.id.btnDeleteDuplicates)

        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(view)
        btnCloseDuplicates.setOnClickListener { dialog.dismiss() }

        var duplicateGroups = DuplicateFinderHelper.findDuplicates(
            TelegramRepository.photos.value,
            TelegramRepository.videos.value,
            TelegramRepository.audios.value,
            TelegramRepository.files.value
        )

        val selectedItems = mutableSetOf<VaultMediaItem>()

        fun updateUI() {
            if (duplicateGroups.isEmpty()) {
                layoutEmptyDuplicates.visibility = View.VISIBLE
                rvDuplicates.visibility = View.GONE
                tvDuplicatesSummary.text = "No duplicate files found in vault"
                btnDeleteDuplicates.isEnabled = false
                btnDeleteDuplicates.alpha = 0.5f
                btnDeleteDuplicates.text = "🗑 Delete Selected Duplicates"
            } else {
                layoutEmptyDuplicates.visibility = View.GONE
                rvDuplicates.visibility = View.VISIBLE

                val totalDuplicateFiles = duplicateGroups.sumOf { it.items.size - 1 }
                val totalWasted = duplicateGroups.sumOf { it.wastedSizeBytes }
                tvDuplicatesSummary.text = "Found ${duplicateGroups.size} groups • $totalDuplicateFiles duplicate copies • ${CacheManager.formatBytes(totalWasted)} wasted"

                val selectedSize = selectedItems.sumOf { it.sizeBytes }
                val selectedCount = selectedItems.size
                if (selectedCount > 0) {
                    btnDeleteDuplicates.isEnabled = true
                    btnDeleteDuplicates.alpha = 1.0f
                    btnDeleteDuplicates.text = "🗑 Delete $selectedCount Selected (${CacheManager.formatBytes(selectedSize)})"
                } else {
                    btnDeleteDuplicates.isEnabled = false
                    btnDeleteDuplicates.alpha = 0.5f
                    btnDeleteDuplicates.text = "🗑 Select Duplicates to Delete"
                }
            }
        }

        lateinit var adapter: DuplicatesAdapter

        fun autoSelectKeepOldest() {
            selectedItems.clear()
            for (group in duplicateGroups) {
                // items sorted newest first, so last is oldest
                val oldest = group.items.lastOrNull()
                for (item in group.items) {
                    if (item != oldest) {
                        selectedItems.add(item)
                    }
                }
            }
            adapter.notifyDataSetChanged()
            updateUI()
        }

        fun autoSelectKeepNewest() {
            selectedItems.clear()
            for (group in duplicateGroups) {
                // items sorted newest first, so first is newest
                val newest = group.items.firstOrNull()
                for (item in group.items) {
                    if (item != newest) {
                        selectedItems.add(item)
                    }
                }
            }
            adapter.notifyDataSetChanged()
            updateUI()
        }

        btnKeepOldest.setOnClickListener { autoSelectKeepOldest() }
        btnKeepNewest.setOnClickListener { autoSelectKeepNewest() }
        btnClearDuplicatesSelection.setOnClickListener {
            selectedItems.clear()
            adapter.notifyDataSetChanged()
            updateUI()
        }

        adapter = DuplicatesAdapter(activity, duplicateGroups, selectedItems) {
            updateUI()
        }

        rvDuplicates.layoutManager = LinearLayoutManager(activity)
        rvDuplicates.adapter = adapter

        // Auto-select keep oldest by default so user can immediately clean up
        autoSelectKeepOldest()

        btnDeleteDuplicates.setOnClickListener {
            val toDelete = selectedItems.toList()
            if (toDelete.isEmpty()) return@setOnClickListener

            val count = toDelete.size
            val sizeStr = CacheManager.formatBytes(toDelete.sumOf { it.sizeBytes })

            AlertDialog.Builder(activity)
                .setTitle("Delete $count Duplicate(s)?")
                .setMessage("Are you sure you want to permanently delete $count duplicate item(s) ($sizeStr) from Telegram Cloud?")
                .setPositiveButton("Delete Permanently") { _, _ ->
                    val lifecycleOwner = activity as? LifecycleOwner
                    val scope = lifecycleOwner?.lifecycleScope ?: kotlinx.coroutines.GlobalScope

                    scope.launch(Dispatchers.IO) {
                        val success = TelegramRepository.deleteMediaItems(toDelete)
                        withContext(Dispatchers.Main) {
                            if (success) {
                                Toast.makeText(activity, "Deleted $count duplicate(s)! Reclaimed $sizeStr 🎉", Toast.LENGTH_LONG).show()
                                selectedItems.clear()
                                duplicateGroups = DuplicateFinderHelper.findDuplicates(
                                    TelegramRepository.photos.value,
                                    TelegramRepository.videos.value,
                                    TelegramRepository.audios.value,
                                    TelegramRepository.files.value
                                )
                                adapter.updateData(duplicateGroups)
                                updateUI()
                            } else {
                                Toast.makeText(activity, "Failed to delete some items", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        dialog.show()
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
    }

    private class DuplicatesAdapter(
        private val context: Context,
        private var groups: List<DuplicateGroup>,
        private val selectedItems: MutableSet<VaultMediaItem>,
        private val onSelectionChanged: () -> Unit
    ) : RecyclerView.Adapter<DuplicatesAdapter.GroupViewHolder>() {

        private val dateFormat = SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.getDefault())

        fun updateData(newGroups: List<DuplicateGroup>) {
            this.groups = newGroups
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_duplicate_group, parent, false)
            return GroupViewHolder(view)
        }

        override fun getItemCount(): Int = groups.size

        override fun onBindViewHolder(holder: GroupViewHolder, position: Int) {
            val group = groups[position]
            holder.tvGroupTitle.text = group.title
            val icon = when (group.previewItem.type) {
                MediaType.PHOTO -> "📷"
                MediaType.VIDEO -> "🎬"
                MediaType.AUDIO -> "🎵"
                MediaType.DOCUMENT -> "📄"
            }
            holder.tvGroupIcon.text = icon
            holder.tvGroupMeta.text = "${group.items.size} copies • Total ${CacheManager.formatBytes(group.totalSizeBytes)}"
            holder.tvGroupWastedBadge.text = "-${CacheManager.formatBytes(group.wastedSizeBytes)}"

            holder.layoutGroupItems.removeAllViews()

            val oldest = group.items.lastOrNull()

            for (item in group.items) {
                val isOldest = item == oldest
                val dateStr = dateFormat.format(Date(item.dateAdded))
                val label = if (isOldest) "$dateStr  (Original)" else "$dateStr  (Duplicate)"

                val rowLayout = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(0, 8, 0, 8)
                }

                val cb = CheckBox(context).apply {
                    isChecked = selectedItems.contains(item)
                    setOnCheckedChangeListener { _, isChecked ->
                        if (isChecked) selectedItems.add(item) else selectedItems.remove(item)
                        onSelectionChanged()
                    }
                }

                val tvInfo = TextView(context).apply {
                    text = label
                    setTextColor(if (isOldest) Color.parseColor("#7DD3FC") else Color.parseColor("#E2E8F0"))
                    textSize = 12.5f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
                }

                rowLayout.addView(cb)
                rowLayout.addView(tvInfo)
                rowLayout.setOnClickListener {
                    cb.isChecked = !cb.isChecked
                }

                holder.layoutGroupItems.addView(rowLayout)
            }
        }

        private fun spToFloat(context: Context, sp: Int): Float = sp.toFloat()

        class GroupViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvGroupIcon: TextView = view.findViewById(R.id.tvGroupIcon)
            val tvGroupTitle: TextView = view.findViewById(R.id.tvGroupTitle)
            val tvGroupMeta: TextView = view.findViewById(R.id.tvGroupMeta)
            val tvGroupWastedBadge: TextView = view.findViewById(R.id.tvGroupWastedBadge)
            val layoutGroupItems: LinearLayout = view.findViewById(R.id.layoutGroupItems)
        }
    }
}
