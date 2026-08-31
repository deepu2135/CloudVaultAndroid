package com.cloudvault.app

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

class FolderSelectionAdapter(
    private var folders: List<DeviceFolderInfo>,
    private val onFolderClick: (DeviceFolderInfo) -> Unit
) : RecyclerView.Adapter<FolderSelectionAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cardFolder: MaterialCardView = view.findViewById(R.id.cardFolder)
        val cbFolder: CheckBox = view.findViewById(R.id.cbFolder)
        val tvFolderName: TextView = view.findViewById(R.id.tvFolderName)
        val tvFolderItemCount: TextView = view.findViewById(R.id.tvFolderItemCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_folder_selection, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val folder = folders[position]
        holder.tvFolderName.text = folder.bucketName
        holder.tvFolderItemCount.text = "${folder.totalCount} items"
        holder.cbFolder.isChecked = folder.isSelected

        val context = holder.itemView.context
        if (folder.isSelected) {
            holder.cardFolder.strokeColor = context.getColor(R.color.accent_cyan)
            holder.cardFolder.setCardBackgroundColor(context.getColor(R.color.status_pill_bg))
        } else {
            holder.cardFolder.strokeColor = context.getColor(R.color.card_border)
            holder.cardFolder.setCardBackgroundColor(context.getColor(R.color.bg_surface_elevated))
        }

        holder.cardFolder.setOnClickListener {
            folder.isSelected = !folder.isSelected
            notifyItemChanged(position)
            onFolderClick(folder)
        }
    }

    override fun getItemCount() = folders.size

    fun updateData(newFolders: List<DeviceFolderInfo>) {
        folders = newFolders
        notifyDataSetChanged()
    }
}
