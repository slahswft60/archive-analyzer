package com.example.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.R;
import com.example.model.ArchiveEntry;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ArchiveAdapter extends RecyclerView.Adapter<ArchiveAdapter.ViewHolder> {

    public interface OnEntryClickListener {
        void onDownloadClick(ArchiveEntry entry);
    }

    private final List<ArchiveEntry> allEntries = new ArrayList<>();
    private final List<ArchiveEntry> displayedEntries = new ArrayList<>();
    private final OnEntryClickListener listener;

    public ArchiveAdapter(OnEntryClickListener listener) {
        this.listener = listener;
    }

    public void setEntries(List<ArchiveEntry> entries) {
        allEntries.clear();
        displayedEntries.clear();
        if (entries != null) {
            allEntries.addAll(entries);
            displayedEntries.addAll(entries);
        }
        notifyDataSetChanged();
    }

    public void filter(String query) {
        displayedEntries.clear();
        if (query == null || query.trim().isEmpty()) {
            displayedEntries.addAll(allEntries);
        } else {
            String lower = query.trim().toLowerCase(Locale.ROOT);
            for (ArchiveEntry entry : allEntries) {
                if (entry.getName().toLowerCase(Locale.ROOT).contains(lower)) {
                    displayedEntries.add(entry);
                }
            }
        }
        notifyDataSetChanged();
    }

    public int getDisplayedCount() {
        return displayedEntries.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_archive_entry, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ArchiveEntry entry = displayedEntries.get(position);
        holder.bind(entry, listener);
    }

    @Override
    public int getItemCount() {
        return displayedEntries.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        private final ImageView imgIcon;
        private final TextView txtFileName;
        private final TextView txtFilePath;
        private final TextView badgeSize;
        private final TextView badgeFormat;
        private final TextView badgeLz4;
        private final MaterialButton btnDownload;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            imgIcon = itemView.findViewById(R.id.img_file_icon);
            txtFileName = itemView.findViewById(R.id.txt_file_name);
            txtFilePath = itemView.findViewById(R.id.txt_file_path);
            badgeSize = itemView.findViewById(R.id.badge_size);
            badgeFormat = itemView.findViewById(R.id.badge_format);
            badgeLz4 = itemView.findViewById(R.id.badge_lz4);
            btnDownload = itemView.findViewById(R.id.btn_download_entry);
        }

        void bind(ArchiveEntry entry, OnEntryClickListener listener) {
            txtFileName.setText(entry.getSimpleFileName());

            String dirPath = entry.getDirectoryPath();
            if (dirPath.isEmpty()) {
                txtFilePath.setText(entry.getName());
            } else {
                txtFilePath.setText(dirPath);
            }

            badgeSize.setText(entry.getFormattedSize());
            badgeFormat.setText(entry.getCompressionLabel());

            if (entry.isLz4()) {
                badgeLz4.setVisibility(View.VISIBLE);
            } else {
                badgeLz4.setVisibility(View.GONE);
            }

            if (entry.isDirectory()) {
                imgIcon.setImageResource(R.drawable.ic_folder);
                btnDownload.setVisibility(View.GONE);
            } else {
                btnDownload.setVisibility(View.VISIBLE);
                if (entry.isLz4()) {
                    imgIcon.setImageResource(R.drawable.ic_archive);
                } else {
                    String nameLower = entry.getName().toLowerCase(Locale.ROOT);
                    if (nameLower.endsWith(".zip") || nameLower.endsWith(".tar") || nameLower.endsWith(".gz") || nameLower.endsWith(".bin") || nameLower.endsWith(".img")) {
                        imgIcon.setImageResource(R.drawable.ic_archive);
                    } else {
                        imgIcon.setImageResource(R.drawable.ic_file);
                    }
                }
            }

            btnDownload.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onDownloadClick(entry);
                }
            });
        }
    }
}
