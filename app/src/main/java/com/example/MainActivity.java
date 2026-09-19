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
import java.util.ArrayList;
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

    private MaterialCardView cardNestedNav;
    private MaterialButton btnBackToParentArchive;
    private TextView txtNestedArchiveTitle;
    private TextView txtNestedArchiveSubtitle;

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
    private String currentArchiveType = "ZIP"; // "ZIP" or "TAR" or "NESTED_TAR"
    private File lastDownloadedFile;

    // State preservation for navigating inside a nested TAR/TAR.MD5 and coming back
    private List<ArchiveEntry> parentArchiveEntries = new ArrayList<>();
    private String parentArchiveFormat = "ZIP";
    private ArchiveEntry currentNestedTarEntry = null;

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

        cardNestedNav = findViewById(R.id.card_nested_nav);
        btnBackToParentArchive = findViewById(R.id.btn_back_to_parent_archive);
        txtNestedArchiveTitle = findViewById(R.id.txt_nested_archive_title);
        txtNestedArchiveSubtitle = findViewById(R.id.txt_nested_archive_subtitle);

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
            if (clipboard != null && clipboard.hasPrimaryClip()) {
                ClipData clipData = clipboard.getPrimaryClip();
                if (clipData != null && clipData.getItemCount() > 0) {
                    CharSequence text = clipData.getItemAt(0).getText();
                    if (text != null && text.length() > 0) {
                        editArchiveUrl.setText(text.toString().trim());
                        Toast.makeText(this, "URL pasted from clipboard", Toast.LENGTH_SHORT).show();
                    }
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

        btnBackToParentArchive.setOnClickListener(v -> navigateBackToParentArchive());

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
        cardNestedNav.setVisibility(View.GONE);
        currentNestedTarEntry = null;
        parentArchiveEntries.clear();

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
                parentArchiveFormat = detectedFormat;
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
        cardNestedNav.setVisibility(View.GONE);

        txtArchiveFormat.setText(format.equalsIgnoreCase("TAR") ? "TAR Archive" : "ZIP Archive");
        txtArchiveSize.setText("Archive: " + ArchiveEntry.formatBytes(fileInfo.getContentLength()));
        txtFilesCount.setText(entries.size() + " files found in central directory (without downloading full archive)");

        parentArchiveEntries = new ArrayList<>(entries);
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
    public void onAnalyzeTarClick(ArchiveEntry entry) {
        if (currentRemoteFile == null) {
            Toast.makeText(this, "Please analyze an archive first", Toast.LENGTH_SHORT).show();
            return;
        }

        // Trigger remote TAR parsing inside ZIP without downloading the full multi-GB archive
        startRemoteTarMd5Analysis(entry);
    }

    private void startRemoteTarMd5Analysis(ArchiveEntry tarEntry) {
        setLoading(true, "Analyzing " + tarEntry.getSimpleFileName() + " remotely...",
                "Downloading first 8KB header to detect TAR structure...");

        executor.execute(() -> {
            try {
                String resolvedUrl = currentRemoteFile.getResolvedUrl();
                long dataStartOffset;

                if ("TAR".equalsIgnoreCase(currentArchiveType) || "NESTED_TAR".equalsIgnoreCase(currentArchiveType)) {
                    // Entry is directly in a TAR archive
                    dataStartOffset = tarEntry.getDataOffset();
                    if (dataStartOffset <= 0) {
                        dataStartOffset = tarEntry.getHeaderOffset() + 512;
                    }
                } else {
                    // Entry is inside a ZIP archive: resolve Local File Header offset
                    RemoteZipParser zipParser = new RemoteZipParser(httpClient);
                    dataStartOffset = zipParser.resolveEntryDataOffset(resolvedUrl, tarEntry);
                }

                // Check compression method
                if (tarEntry.getCompressionMethod() > 0 && tarEntry.getCompressionMethod() != -1) {
                    throw new IOException("The file " + tarEntry.getSimpleFileName() +
                            " is stored with ZIP Deflate compression (method " + tarEntry.getCompressionMethod() +
                            "). Remote nested TAR parsing requires Stored (uncompressed) entry to seek byte offsets directly.");
                }

                long tarLength = tarEntry.getUncompressedSize();

                // Check for TAR headers in first 8KB
                RemoteTarParser tarParser = new RemoteTarParser(httpClient);
                boolean isValidTar = tarParser.verifyTarHeader(resolvedUrl, dataStartOffset);
                if (!isValidTar) {
                    throw new IOException("The entry " + tarEntry.getSimpleFileName() +
                            " does not contain a standard TAR/ustar header at byte offset " + dataStartOffset);
                }

                mainHandler.post(() -> setLoading(true, "Reading TAR blocks remotely...",
                        "Parsing nested headers using 64KB HTTP Range requests..."));

                List<ArchiveEntry> nestedEntries = tarParser.parseTarAtOffset(
                        resolvedUrl,
                        dataStartOffset,
                        tarLength,
                        (count, fileName, offset) -> mainHandler.post(() ->
                                txtLoadingSubmessage.setText("Found: " + fileName + " (" + count + " items)..."))
                );

                mainHandler.post(() -> onNestedTarAnalysisSuccess(tarEntry, nestedEntries));

            } catch (Exception e) {
                e.printStackTrace();
                mainHandler.post(() -> {
                    setLoading(false, null, null);
                    new AlertDialog.Builder(this)
                            .setTitle("Remote TAR Analysis Failed")
                            .setMessage("Could not parse TAR contents remotely:\n\n" + e.getMessage())
                            .setPositiveButton("OK", null)
                            .show();
                });
            }
        });
    }

    private void onNestedTarAnalysisSuccess(ArchiveEntry tarEntry, List<ArchiveEntry> nestedEntries) {
        setLoading(false, null, null);

        if (nestedEntries.isEmpty()) {
            Toast.makeText(this, "No valid files found inside " + tarEntry.getSimpleFileName(), Toast.LENGTH_LONG).show();
            return;
        }

        currentNestedTarEntry = tarEntry;
        currentArchiveType = "NESTED_TAR";

        // Update UI to show nested TAR view
        cardNestedNav.setVisibility(View.VISIBLE);
        txtNestedArchiveTitle.setText(tarEntry.getSimpleFileName());
        txtNestedArchiveSubtitle.setText("Inside ZIP • " + ArchiveEntry.formatBytes(tarEntry.getUncompressedSize()) + " • Parsed remotely via HTTP Range");

        txtArchiveFormat.setText("Nested TAR (.tar.md5)");
        txtArchiveSize.setText("TAR Size: " + ArchiveEntry.formatBytes(tarEntry.getUncompressedSize()));
        txtFilesCount.setText(nestedEntries.size() + " firmware partitions / files inside TAR");

        adapter.setEntries(nestedEntries);
        recyclerArchiveContents.scrollToPosition(0);

        Snackbar.make(recyclerArchiveContents,
                "Parsed " + nestedEntries.size() + " files inside " + tarEntry.getSimpleFileName() + " remotely!",
                Snackbar.LENGTH_LONG).show();
    }

    private void navigateBackToParentArchive() {
        if (parentArchiveEntries.isEmpty()) return;

        currentNestedTarEntry = null;
        currentArchiveType = parentArchiveFormat;

        cardNestedNav.setVisibility(View.GONE);
        txtArchiveFormat.setText(parentArchiveFormat.equalsIgnoreCase("TAR") ? "TAR Archive" : "ZIP Archive");
        if (currentRemoteFile != null) {
            txtArchiveSize.setText("Archive: " + ArchiveEntry.formatBytes(currentRemoteFile.getContentLength()));
        }
        txtFilesCount.setText(parentArchiveEntries.size() + " files found in parent archive");

        adapter.setEntries(parentArchiveEntries);
        recyclerArchiveContents.scrollToPosition(0);
    }

    @Override
    public void onBackPressed() {
        if (currentNestedTarEntry != null) {
            navigateBackToParentArchive();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public void onDownloadClick(ArchiveEntry entry) {
        if (currentRemoteFile == null) {
            Toast.makeText(this, "Please analyze an archive first", Toast.LENGTH_SHORT).show();
            return;
        }

        // If user taps download on a .tar.md5 file inside a ZIP, offer to analyze TAR remotely instead
        // or download single entry directly!
        if (entry.isTarMd5() && !"NESTED_TAR".equalsIgnoreCase(currentArchiveType)) {
            new AlertDialog.Builder(this)
                    .setTitle("Large .tar.md5 Detected (" + entry.getFormattedSize() + ")")
                    .setMessage("This file is a multi-partition TAR archive (" + entry.getSimpleFileName() + ").\n\nWould you like to analyze its partition contents remotely via HTTP Range, or download the full " + entry.getFormattedSize() + " file?")
                    .setPositiveButton("Analyze TAR Remotely", (dialog, which) -> startRemoteTarMd5Analysis(entry))
                    .setNeutralButton("Download Full File", (dialog, which) -> performDownload(entry))
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }

        performDownload(entry);
    }

    private void performDownload(ArchiveEntry entry) {
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
                if ("TAR".equalsIgnoreCase(currentArchiveType) || "NESTED_TAR".equalsIgnoreCase(currentArchiveType)) {
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
