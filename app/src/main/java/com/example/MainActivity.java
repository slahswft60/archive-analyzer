package com.example;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.webkit.MimeTypeMap;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.model.ArchiveEntry;
import com.example.network.HttpRangeClient;
import com.example.network.LocalArchiveServer;
import com.example.parser.RemoteTarParser;
import com.example.parser.RemoteZipParser;
import com.example.ui.ArchiveAdapter;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements ArchiveAdapter.OnEntryClickListener {

    private TextInputEditText editArchiveUrl;
    private MaterialButton btnAnalyze;
    private MaterialButton btnPaste;
    private Chip chipSampleZip;
    private Chip chipSampleTar;

    private MaterialCardView cardArchiveSummary;
    private TextView txtArchiveFormat;
    private TextView txtArchiveSize;
    private TextView txtRangeStatus;
    private TextView txtFilesCount;

    private TextInputLayout layoutSearchFilter;
    private TextInputEditText editSearchFilter;

    private RecyclerView recyclerArchiveContents;
    private ArchiveAdapter adapter;
    private View viewEmptyState;
    private View viewLoadingState;
    private TextView txtLoadingMessage;
    private TextView txtLoadingSubmessage;

    private MaterialCardView cardDownloadProgress;
    private ImageView imgDownloadStatusIcon;
    private TextView txtDownloadFilename;
    private TextView txtDownloadStage;
    private LinearProgressIndicator progressDownload;
    private LinearLayout layoutDownloadSuccessActions;
    private MaterialButton btnShareExtracted;
    private MaterialButton btnOpenExtracted;
    private ImageButton btnCloseDownloadCard;

    private final HttpRangeClient httpClient = new HttpRangeClient();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private HttpRangeClient.RemoteFileInfo currentRemoteFile;
    private String currentArchiveType = "ZIP"; // "ZIP" or "TAR"
    private File lastDownloadedFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            android.util.Log.e("ArchiveAnalyzer", "Uncaught exception in thread " + thread.getName(), throwable);
        });

        setContentView(R.layout.activity_main);

        // Start embedded local test archive server safely in background
        executor.execute(() -> {
            try {
                LocalArchiveServer.getInstance().start();
            } catch (Throwable t) {
                android.util.Log.e("ArchiveAnalyzer", "Local server initialization skipped/failed: " + t.getMessage());
            }
        });

        initViews();
        setupListeners();
    }

    private void initViews() {
        editArchiveUrl = findViewById(R.id.edit_archive_url);
        btnAnalyze = findViewById(R.id.btn_analyze);
        btnPaste = findViewById(R.id.btn_paste);
        chipSampleZip = findViewById(R.id.chip_sample_zip);
        chipSampleTar = findViewById(R.id.chip_sample_tar);

        cardArchiveSummary = findViewById(R.id.card_archive_summary);
        txtArchiveFormat = findViewById(R.id.txt_archive_format);
        txtArchiveSize = findViewById(R.id.txt_archive_size);
        txtRangeStatus = findViewById(R.id.txt_range_status);
        txtFilesCount = findViewById(R.id.txt_files_count);

        layoutSearchFilter = findViewById(R.id.layout_search_filter);
        editSearchFilter = findViewById(R.id.edit_search_filter);

        recyclerArchiveContents = findViewById(R.id.recycler_archive_contents);
        viewEmptyState = findViewById(R.id.view_empty_state);
        viewLoadingState = findViewById(R.id.view_loading_state);
        txtLoadingMessage = findViewById(R.id.txt_loading_message);
        txtLoadingSubmessage = findViewById(R.id.txt_loading_submessage);

        cardDownloadProgress = findViewById(R.id.card_download_progress);
        imgDownloadStatusIcon = findViewById(R.id.img_download_status_icon);
        txtDownloadFilename = findViewById(R.id.txt_download_filename);
        txtDownloadStage = findViewById(R.id.txt_download_stage);
        progressDownload = findViewById(R.id.progress_download);
        layoutDownloadSuccessActions = findViewById(R.id.layout_download_success_actions);
        btnShareExtracted = findViewById(R.id.btn_share_extracted);
        btnOpenExtracted = findViewById(R.id.btn_open_extracted);
        btnCloseDownloadCard = findViewById(R.id.btn_close_download_card);

        recyclerArchiveContents.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ArchiveAdapter(this);
        recyclerArchiveContents.setAdapter(adapter);
    }

    private void setupListeners() {
        btnAnalyze.setOnClickListener(v -> {
            String url = editArchiveUrl.getText() != null ? editArchiveUrl.getText().toString().trim() : "";
            if (url.isEmpty()) {
                Toast.makeText(this, "Please enter an archive URL", Toast.LENGTH_SHORT).show();
                return;
            }
            startRemoteAnalysis(url);
        });

        btnPaste.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null && clipboard.hasPrimaryClip() && clipboard.getPrimaryClip() != null) {
                ClipData.Item item = clipboard.getPrimaryClip().getItemAt(0);
                if (item != null && item.getText() != null) {
                    editArchiveUrl.setText(item.getText().toString().trim());
                    Toast.makeText(this, "URL pasted from clipboard", Toast.LENGTH_SHORT).show();
                }
            }
        });

        chipSampleZip.setOnClickListener(v -> {
            String demoUrl = LocalArchiveServer.getInstance().getDemoZipUrl();
            editArchiveUrl.setText(demoUrl);
            startRemoteAnalysis(demoUrl);
        });

        chipSampleTar.setOnClickListener(v -> {
            String demoUrl = LocalArchiveServer.getInstance().getDemoTarUrl();
            editArchiveUrl.setText(demoUrl);
            startRemoteAnalysis(demoUrl);
        });

        editSearchFilter.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                adapter.filter(s != null ? s.toString() : "");
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        btnCloseDownloadCard.setOnClickListener(v -> cardDownloadProgress.setVisibility(View.GONE));

        btnOpenExtracted.setOnClickListener(v -> {
            if (lastDownloadedFile != null && lastDownloadedFile.exists()) {
                openFile(lastDownloadedFile);
            }
        });

        btnShareExtracted.setOnClickListener(v -> {
            if (lastDownloadedFile != null && lastDownloadedFile.exists()) {
                shareFile(lastDownloadedFile);
            }
        });
    }

    private void startRemoteAnalysis(String rawUrl) {
        setLoading(true, "Connecting to remote archive...", "Checking HTTP Range request support...");
        cardArchiveSummary.setVisibility(View.GONE);
        cardDownloadProgress.setVisibility(View.GONE);

        executor.execute(() -> {
            try {
                HttpRangeClient.RemoteFileInfo fileInfo = httpClient.probeRemoteFile(rawUrl);
                currentRemoteFile = fileInfo;

                mainHandler.post(() -> setLoading(true, "Probing archive directory...",
                        "Total size: " + ArchiveEntry.formatBytes(fileInfo.getContentLength()) + " (reading headers only)"));

                List<ArchiveEntry> entries = null;
                String detectedFormat = "ZIP";

                // Auto-detect format based on URL or try ZIP then TAR
                String urlLower = rawUrl.toLowerCase(Locale.ROOT);
                boolean isTarHint = urlLower.contains(".tar");

                if (isTarHint) {
                    try {
                        RemoteTarParser tarParser = new RemoteTarParser(httpClient);
                        entries = tarParser.parseTarArchive(fileInfo.getResolvedUrl(), fileInfo.getContentLength());
                        detectedFormat = "TAR";
                    } catch (Exception e) {
                        // Fallback to ZIP
                        RemoteZipParser zipParser = new RemoteZipParser(httpClient);
                        entries = zipParser.parseCentralDirectory(fileInfo.getResolvedUrl(), fileInfo.getContentLength());
                        detectedFormat = "ZIP";
                    }
                } else {
                    try {
                        RemoteZipParser zipParser = new RemoteZipParser(httpClient);
                        entries = zipParser.parseCentralDirectory(fileInfo.getResolvedUrl(), fileInfo.getContentLength());
                        detectedFormat = "ZIP";
                    } catch (Exception e) {
                        // Fallback to TAR
                        RemoteTarParser tarParser = new RemoteTarParser(httpClient);
                        entries = tarParser.parseTarArchive(fileInfo.getResolvedUrl(), fileInfo.getContentLength());
                        detectedFormat = "TAR";
                    }
                }

                currentArchiveType = detectedFormat;
                List<ArchiveEntry> finalEntries = entries;
                String finalFormat = detectedFormat;

                mainHandler.post(() -> onAnalysisSuccess(fileInfo, finalEntries, finalFormat));

            } catch (Exception e) {
                e.printStackTrace();
                mainHandler.post(() -> onAnalysisFailure(e.getMessage()));
            }
        });
    }

    private void onAnalysisSuccess(HttpRangeClient.RemoteFileInfo fileInfo, List<ArchiveEntry> entries, String format) {
        setLoading(false, null, null);

        if (entries == null || entries.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("Archive Empty")
                    .setMessage("Connected successfully via HTTP Range, but no entries were found in the archive.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        viewEmptyState.setVisibility(View.GONE);
        layoutSearchFilter.setVisibility(View.VISIBLE);
        cardArchiveSummary.setVisibility(View.VISIBLE);

        txtArchiveFormat.setText(format.equalsIgnoreCase("TAR") ? "TAR Archive" : "ZIP Archive");
        txtArchiveSize.setText("Archive: " + ArchiveEntry.formatBytes(fileInfo.getContentLength()));
        txtFilesCount.setText(entries.size() + " files found in central directory (without downloading full archive)");

        adapter.setEntries(entries);
        recyclerArchiveContents.scrollToPosition(0);

        Snackbar.make(recyclerArchiveContents,
                "Analyzed successfully: " + entries.size() + " files parsed via HTTP Range!",
                Snackbar.LENGTH_SHORT).show();
    }

    private void onAnalysisFailure(String errorMessage) {
        setLoading(false, null, null);
        new AlertDialog.Builder(this)
                .setTitle("Analysis Failed")
                .setMessage("Failed to analyze remote archive using HTTP Range requests.\n\nReason: " +
                        (errorMessage != null ? errorMessage : "Unknown network error") +
                        "\n\nPlease ensure:\n• The remote server supports HTTP Range headers (HTTP 206)\n• The URL is a direct download link\n• For Google Drive, ensure link sharing is set to 'Anyone with link'")
                .setPositiveButton("Dismiss", null)
                .show();
    }

    @Override
    public void onDownloadClick(ArchiveEntry entry) {
        if (currentRemoteFile == null) {
            Toast.makeText(this, "Please analyze an archive first", Toast.LENGTH_SHORT).show();
            return;
        }

        cardDownloadProgress.setVisibility(View.VISIBLE);
        imgDownloadStatusIcon.setImageResource(R.drawable.ic_download);
        imgDownloadStatusIcon.setColorFilter(getColor(R.color.primary));
        txtDownloadFilename.setText("Extracting " + entry.getSimpleFileName());
        txtDownloadStage.setText("Preparing HTTP Range request...");
        progressDownload.setIndeterminate(true);
        layoutDownloadSuccessActions.setVisibility(View.GONE);
        btnCloseDownloadCard.setVisibility(View.GONE);

        File downloadDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (downloadDir == null) {
            downloadDir = getFilesDir();
        }

        File targetDir = downloadDir;

        executor.execute(() -> {
            try {
                File extractedFile;
                if ("TAR".equalsIgnoreCase(currentArchiveType)) {
                    RemoteTarParser tarParser = new RemoteTarParser(httpClient);
                    extractedFile = tarParser.downloadAndExtractEntry(
                            currentRemoteFile.getResolvedUrl(),
                            entry,
                            targetDir,
                            (bytesRead, totalBytes, stageMessage) -> mainHandler.post(() -> {
                                txtDownloadStage.setText(stageMessage);
                                if (totalBytes > 0) {
                                    progressDownload.setIndeterminate(false);
                                    int pct = (int) Math.min(100, (bytesRead * 100) / totalBytes);
                                    progressDownload.setProgress(pct);
                                }
                            })
                    );
                } else {
                    RemoteZipParser zipParser = new RemoteZipParser(httpClient);
                    extractedFile = zipParser.downloadAndExtractEntry(
                            currentRemoteFile.getResolvedUrl(),
                            entry,
                            targetDir,
                            (bytesRead, totalBytes, stageMessage) -> mainHandler.post(() -> {
                                txtDownloadStage.setText(stageMessage);
                                if (totalBytes > 0) {
                                    progressDownload.setIndeterminate(false);
                                    int pct = (int) Math.min(100, (bytesRead * 100) / totalBytes);
                                    progressDownload.setProgress(pct);
                                }
                            })
                    );
                }

                lastDownloadedFile = extractedFile;
                mainHandler.post(() -> onDownloadSuccess(entry, extractedFile));

            } catch (Exception e) {
                e.printStackTrace();
                mainHandler.post(() -> onDownloadFailure(entry, e.getMessage()));
            }
        });
    }

    private void onDownloadSuccess(ArchiveEntry entry, File extractedFile) {
        progressDownload.setIndeterminate(false);
        progressDownload.setProgress(100);
        imgDownloadStatusIcon.setImageResource(R.drawable.ic_check_circle);
        imgDownloadStatusIcon.setColorFilter(getColor(R.color.accent_green));

        String decompNote = "";
        if (entry.isLz4() && !extractedFile.getName().toLowerCase(Locale.ROOT).endsWith(".lz4")) {
            decompNote = " (LZ4 decompressed)";
        }

        txtDownloadFilename.setText("Saved: " + extractedFile.getName() + decompNote);
        txtDownloadStage.setText("Path: " + extractedFile.getAbsolutePath() + "\nSize: " + ArchiveEntry.formatBytes(extractedFile.length()));
        layoutDownloadSuccessActions.setVisibility(View.VISIBLE);
        btnCloseDownloadCard.setVisibility(View.VISIBLE);

        Toast.makeText(this, "Extracted " + extractedFile.getName() + " successfully!", Toast.LENGTH_LONG).show();
    }

    private void onDownloadFailure(ArchiveEntry entry, String error) {
        progressDownload.setIndeterminate(false);
        progressDownload.setProgress(0);
        txtDownloadFilename.setText("Failed: " + entry.getSimpleFileName());
        txtDownloadStage.setText("Error: " + (error != null ? error : "Extraction failed"));
        btnCloseDownloadCard.setVisibility(View.VISIBLE);

        new AlertDialog.Builder(this)
                .setTitle("Selective Extraction Failed")
                .setMessage("Could not extract " + entry.getSimpleFileName() + " via HTTP Range.\n\n" + error)
                .setPositiveButton("OK", null)
                .show();
    }

    private void openFile(File file) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            String extension = MimeTypeMap.getFileExtensionFromUrl(file.getName());
            String mimeType = extension != null ? MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase(Locale.ROOT)) : null;
            if (mimeType == null) mimeType = "*/*";
            intent.setDataAndType(uri, mimeType);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "Open " + file.getName()));
        } catch (Exception e) {
            Toast.makeText(this, "No app available to open this file type (" + file.getName() + ")", Toast.LENGTH_SHORT).show();
        }
    }

    private void shareFile(File file) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            String extension = MimeTypeMap.getFileExtensionFromUrl(file.getName());
            String mimeType = extension != null ? MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase(Locale.ROOT)) : null;
            if (mimeType == null) mimeType = "*/*";
            shareIntent.setType(mimeType);
            shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(shareIntent, "Share " + file.getName()));
        } catch (Exception e) {
            Toast.makeText(this, "Unable to share file: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void setLoading(boolean loading, String message, String submessage) {
        btnAnalyze.setEnabled(!loading);
        if (loading) {
            viewLoadingState.setVisibility(View.VISIBLE);
            if (message != null) txtLoadingMessage.setText(message);
            if (submessage != null) txtLoadingSubmessage.setText(submessage);
        } else {
            viewLoadingState.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}
