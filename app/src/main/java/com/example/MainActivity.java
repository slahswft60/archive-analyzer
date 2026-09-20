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
import com.example.network.CloudUnpackManager;
import com.example.network.HttpRangeClient;
import com.example.network.LocalArchiveServer;
import com.example.parser.RemoteTarParser;
import com.example.parser.RemoteZipParser;
import com.example.parser.StreamingTarExtractor;
import com.example.service.ArchiveHeaderService;
import com.example.service.ArchiveParseResult;
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
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity implements ArchiveAdapter.OnEntryClickListener {

    private TextInputEditText editArchiveUrl;
    private MaterialButton btnAnalyze;
    private MaterialButton btnPaste;
    private Chip chipSampleZip;
    private Chip chipSampleTar;
    private Chip chipSampleTarMd5;
    private Chip chipCloudSettings;
    private CloudUnpackManager cloudUnpackManager;

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
    private MaterialButton btnCancelDownload;
    private LinearLayout layoutDownloadActiveControls;

    private final AtomicBoolean activeCancelSignal = new AtomicBoolean(false);

    private final HttpRangeClient httpClient = new HttpRangeClient();
    private final ArchiveHeaderService archiveHeaderService = new ArchiveHeaderService(httpClient);
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
        cloudUnpackManager = new CloudUnpackManager(this);
        setupListeners();
    }

    private void initViews() {
        editArchiveUrl = findViewById(R.id.edit_archive_url);
        btnAnalyze = findViewById(R.id.btn_analyze);
        btnPaste = findViewById(R.id.btn_paste);
        chipSampleZip = findViewById(R.id.chip_sample_zip);
        chipSampleTar = findViewById(R.id.chip_sample_tar);
        chipSampleTarMd5 = findViewById(R.id.chip_sample_tarmd5);
        chipCloudSettings = findViewById(R.id.chip_cloud_settings);

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
        btnCancelDownload = findViewById(R.id.btn_cancel_download);
        layoutDownloadActiveControls = findViewById(R.id.layout_download_active_controls);

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

        chipSampleTarMd5.setOnClickListener(v -> {
            String demoUrl = LocalArchiveServer.getInstance().getDemoTarMd5Url();
            editArchiveUrl.setText(demoUrl);
            startRemoteAnalysis(demoUrl);
        });

        chipCloudSettings.setOnClickListener(v -> showCloudSettingsDialog());

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

        btnCancelDownload.setOnClickListener(v -> {
            activeCancelSignal.set(true);
            txtDownloadStage.setText("Cancelling extraction...");
            btnCancelDownload.setEnabled(false);
        });

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
        String initialStage = com.example.network.MediaFireResolver.isMediaFireUrl(rawUrl)
                ? "Resolving direct link from MediaFire..."
                : "Connecting to remote archive...";
        setLoading(true, initialStage, "Checking HTTP Range request support...");
        cardArchiveSummary.setVisibility(View.GONE);
        cardDownloadProgress.setVisibility(View.GONE);
        cardNestedNav.setVisibility(View.GONE);
        currentNestedTarEntry = null;
        parentArchiveEntries.clear();

        executor.execute(() -> {
            try {
                ArchiveParseResult parseResult = archiveHeaderService.parseRemoteArchive(rawUrl, (stage, details) -> {
                    mainHandler.post(() -> setLoading(true, stage, details));
                });

                currentRemoteFile = new HttpRangeClient.RemoteFileInfo(
                        parseResult.getOriginalUrl(),
                        parseResult.getResolvedUrl(),
                        parseResult.getTotalFileSize(),
                        parseResult.isSupportsRange()
                );

                currentArchiveType = parseResult.getArchiveTypeString();
                parentArchiveFormat = currentArchiveType;
                List<ArchiveEntry> finalEntries = parseResult.getEntries();
                String finalFormat = currentArchiveType;

                mainHandler.post(() -> onAnalysisSuccess(currentRemoteFile, finalEntries, finalFormat));

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
                boolean isParentTar = "TAR".equalsIgnoreCase(currentArchiveType) || "NESTED_TAR".equalsIgnoreCase(currentArchiveType);
                AtomicBoolean scanCancel = new AtomicBoolean(false);

                List<ArchiveEntry> nestedEntries = archiveHeaderService.parseNestedTar(
                        resolvedUrl,
                        tarEntry,
                        isParentTar,
                        scanCancel,
                        (stage, details) -> mainHandler.post(() -> setLoading(true, stage, details))
                );

                for (ArchiveEntry e : nestedEntries) {
                    e.setParentArchiveEntry(tarEntry);
                }

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

        // Streaming nested extraction check:
        // When entry is a firmware image (.img/.bin or .lz4) inside a nested compressed TAR (.tar.md5 inside ZIP):
        ArchiveEntry parent = entry.getParentArchiveEntry() != null ? entry.getParentArchiveEntry() : currentNestedTarEntry;
        if (parent != null && parent.isTarMd5() && parent.getCompressionMethod() == 8 && entry.isFirmwareImage()) {
            String targetName = entry.getSimpleFileName();
            String targetSize = entry.getFormattedSize();
            String parentName = parent.getSimpleFileName();
            String parentCompSize = parent.getFormattedCompressedSize();

            new AlertDialog.Builder(this)
                    .setTitle("Extract " + targetName)
                    .setMessage("How would you like to extract " + targetName + " (" + targetSize + ") from " + parentName + " (" + parentCompSize + ")?\n\n" +
                            "• Cloud Unpack (0% ROM Data Transfer): The cloud worker decompresses the ROM at datacenter speed and sends only " + targetName + " to your device.\n\n" +
                            "• Local Streaming: Your device downloads and stream-decompresses the archive sequentially until finding " + targetName + ".")
                    .setPositiveButton("☁️ Cloud Unpack (64 MB only)", (dialog, which) -> performCloudUnpackExtraction(entry, parent))
                    .setNeutralButton("📱 Local Stream", (dialog, which) -> performStreamingNestedExtraction(entry, parent))
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }

        performDownload(entry);
    }

    private void showCloudSettingsDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_cloud_settings, null);
        com.google.android.material.materialswitch.MaterialSwitch switchDefault = dialogView.findViewById(R.id.switch_cloud_default);
        TextInputEditText editWorkerUrl = dialogView.findViewById(R.id.edit_worker_url);

        switchDefault.setChecked(cloudUnpackManager.isUseCloudByDefault());
        editWorkerUrl.setText(cloudUnpackManager.getCustomWorkerUrl());

        new AlertDialog.Builder(this)
                .setView(dialogView)
                .setPositiveButton("Save", (dialog, which) -> {
                    cloudUnpackManager.setUseCloudByDefault(switchDefault.isChecked());
                    String newUrl = editWorkerUrl.getText() != null ? editWorkerUrl.getText().toString().trim() : "";
                    cloudUnpackManager.setCustomWorkerUrl(newUrl);
                    Toast.makeText(this, "Cloud Unpack settings saved", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void performCloudUnpackExtraction(ArchiveEntry targetEntry, ArchiveEntry parentZipEntry) {
        activeCancelSignal.set(false);
        cardDownloadProgress.setVisibility(View.VISIBLE);
        layoutDownloadActiveControls.setVisibility(View.VISIBLE);
        btnCancelDownload.setEnabled(true);
        imgDownloadStatusIcon.setImageResource(R.drawable.ic_cloud_download);
        imgDownloadStatusIcon.setColorFilter(getColor(R.color.accent_purple));
        txtDownloadFilename.setText("Cloud Unpacking " + targetEntry.getSimpleFileName());
        txtDownloadStage.setText("Requesting Cloud Worker to unpack partition (0 MB phone ROM data transfer)...");
        progressDownload.setIndeterminate(true);
        layoutDownloadSuccessActions.setVisibility(View.GONE);
        btnCloseDownloadCard.setVisibility(View.GONE);

        String cloudUrl = cloudUnpackManager.buildCloudUnpackUrl(
                currentRemoteFile.getResolvedUrl(),
                parentZipEntry.getSimpleFileName(),
                targetEntry.getSimpleFileName()
        );

        File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (downloadDir == null || !downloadDir.exists()) {
            downloadDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        }
        if (downloadDir == null) {
            downloadDir = getFilesDir();
        }

        File targetDir = downloadDir;

        executor.execute(() -> {
            try {
                // Download directly from Cloud Worker endpoint - transfer only the target partition size
                File destFile = new File(targetDir, targetEntry.getSimpleFileName());
                if (destFile.exists()) {
                    destFile.delete();
                }

                okhttp3.Request request = new okhttp3.Request.Builder()
                        .url(cloudUrl)
                        .header("User-Agent", "ArchiveAnalyzer-CloudUnpack/1.0")
                        .build();

                try (okhttp3.Response response = new okhttp3.OkHttpClient().newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        throw new IOException("Cloud Unpack request failed with HTTP " + response.code() + ": " + response.message());
                    }

                    okhttp3.ResponseBody body = response.body();
                    if (body == null) {
                        throw new IOException("Empty response body from Cloud Unpack service");
                    }

                    long contentLength = body.contentLength();
                    long downloaded = 0;

                    try (java.io.InputStream in = body.byteStream();
                         java.io.FileOutputStream fos = new java.io.FileOutputStream(destFile)) {
                        byte[] buffer = new byte[32 * 1024];
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            if (activeCancelSignal.get()) {
                                destFile.delete();
                                throw new IOException("Cloud unpack cancelled by user");
                            }
                            fos.write(buffer, 0, read);
                            downloaded += read;
                            long finalDownloaded = downloaded;
                            mainHandler.post(() -> {
                                if (contentLength > 0) {
                                    progressDownload.setIndeterminate(false);
                                    int pct = (int) Math.min(100, (finalDownloaded * 100) / contentLength);
                                    progressDownload.setProgress(pct);
                                    txtDownloadStage.setText("Downloaded " + ArchiveEntry.formatBytes(finalDownloaded) + " / " + ArchiveEntry.formatBytes(contentLength) + " (From Cloud Unpack)");
                                } else {
                                    txtDownloadStage.setText("Received " + ArchiveEntry.formatBytes(finalDownloaded) + " from Cloud Worker...");
                                }
                            });
                        }
                        fos.flush();
                    }
                }

                lastDownloadedFile = destFile;
                mainHandler.post(() -> {
                    layoutDownloadActiveControls.setVisibility(View.GONE);
                    onDownloadSuccess(targetEntry, destFile);
                });

            } catch (Exception e) {
                e.printStackTrace();
                mainHandler.post(() -> {
                    layoutDownloadActiveControls.setVisibility(View.GONE);
                    if (activeCancelSignal.get()) {
                        txtDownloadFilename.setText("Cancelled");
                        txtDownloadStage.setText("Cloud extraction was cancelled by user.");
                        progressDownload.setIndeterminate(false);
                        progressDownload.setProgress(0);
                        btnCloseDownloadCard.setVisibility(View.VISIBLE);
                        Toast.makeText(this, "Extraction cancelled", Toast.LENGTH_SHORT).show();
                    } else {
                        onDownloadFailure(targetEntry, "Cloud Unpack Error: " + e.getMessage());
                    }
                });
            }
        });
    }

    private void performStreamingNestedExtraction(ArchiveEntry targetEntry, ArchiveEntry parentZipEntry) {
        activeCancelSignal.set(false);
        cardDownloadProgress.setVisibility(View.VISIBLE);
        layoutDownloadActiveControls.setVisibility(View.VISIBLE);
        btnCancelDownload.setEnabled(true);
        imgDownloadStatusIcon.setImageResource(R.drawable.ic_download);
        imgDownloadStatusIcon.setColorFilter(getColor(R.color.primary));
        txtDownloadFilename.setText("Streaming " + targetEntry.getSimpleFileName());
        txtDownloadStage.setText("Downloaded: 0 B / " + parentZipEntry.getFormattedCompressedSize() + " (Reading headers...)");
        progressDownload.setIndeterminate(true);
        layoutDownloadSuccessActions.setVisibility(View.GONE);
        btnCloseDownloadCard.setVisibility(View.GONE);

        File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (downloadDir == null || !downloadDir.exists()) {
            downloadDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        }
        if (downloadDir == null) {
            downloadDir = getFilesDir();
        }

        File targetDir = downloadDir;

        executor.execute(() -> {
            try {
                StreamingTarExtractor extractor = new StreamingTarExtractor(httpClient);
                File extractedFile = extractor.extractSingleEntryFromCompressedTar(
                        currentRemoteFile.getResolvedUrl(),
                        parentZipEntry,
                        targetEntry.getSimpleFileName(),
                        targetDir,
                        activeCancelSignal,
                        new StreamingTarExtractor.StreamExtractListener() {
                            @Override
                            public void onDownloadProgress(long downloadedBytes, long totalCompressedBytes, String statusMessage) {
                                mainHandler.post(() -> {
                                    txtDownloadStage.setText(statusMessage);
                                    if (totalCompressedBytes > 0) {
                                        progressDownload.setIndeterminate(false);
                                        int pct = (int) Math.min(100, (downloadedBytes * 100) / totalCompressedBytes);
                                        progressDownload.setProgress(pct);
                                    }
                                });
                            }

                            @Override
                            public void onFileDiscovered(String fileName, String statusMessage) {
                                mainHandler.post(() -> txtDownloadStage.setText(statusMessage));
                            }

                            @Override
                            public void onExtractProgress(long bytesWritten, long totalFileSize, String statusMessage) {
                                mainHandler.post(() -> {
                                    txtDownloadStage.setText(statusMessage);
                                    if (totalFileSize > 0) {
                                        progressDownload.setIndeterminate(false);
                                        int pct = (int) Math.min(100, (bytesWritten * 100) / totalFileSize);
                                        progressDownload.setProgress(pct);
                                    }
                                });
                            }
                        }
                );

                lastDownloadedFile = extractedFile;
                mainHandler.post(() -> {
                    layoutDownloadActiveControls.setVisibility(View.GONE);
                    onDownloadSuccess(targetEntry, extractedFile);
                });

            } catch (Exception e) {
                e.printStackTrace();
                mainHandler.post(() -> {
                    layoutDownloadActiveControls.setVisibility(View.GONE);
                    if (activeCancelSignal.get()) {
                        txtDownloadFilename.setText("Cancelled");
                        txtDownloadStage.setText("Extraction was cancelled by user.");
                        progressDownload.setIndeterminate(false);
                        progressDownload.setProgress(0);
                        btnCloseDownloadCard.setVisibility(View.VISIBLE);
                        Toast.makeText(this, "Extraction cancelled", Toast.LENGTH_SHORT).show();
                    } else {
                        onDownloadFailure(targetEntry, e.getMessage());
                    }
                });
            }
        });
    }

    private void performDownload(ArchiveEntry entry) {
        activeCancelSignal.set(false);
        cardDownloadProgress.setVisibility(View.VISIBLE);
        layoutDownloadActiveControls.setVisibility(View.VISIBLE);
        btnCancelDownload.setEnabled(true);
        imgDownloadStatusIcon.setImageResource(R.drawable.ic_download);
        imgDownloadStatusIcon.setColorFilter(getColor(R.color.primary));
        txtDownloadFilename.setText("Extracting " + entry.getSimpleFileName());
        txtDownloadStage.setText("Preparing HTTP Range request...");
        progressDownload.setIndeterminate(true);
        layoutDownloadSuccessActions.setVisibility(View.GONE);
        btnCloseDownloadCard.setVisibility(View.GONE);

        File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (downloadDir == null || !downloadDir.exists()) {
            downloadDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        }
        if (downloadDir == null) {
            downloadDir = getFilesDir();
        }

        File targetDir = downloadDir;

        executor.execute(() -> {
            try {
                boolean isTar = "TAR".equalsIgnoreCase(currentArchiveType) || "NESTED_TAR".equalsIgnoreCase(currentArchiveType);
                File extractedFile = archiveHeaderService.extractEntry(
                        currentRemoteFile.getResolvedUrl(),
                        entry,
                        isTar,
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

                lastDownloadedFile = extractedFile;
                mainHandler.post(() -> {
                    layoutDownloadActiveControls.setVisibility(View.GONE);
                    onDownloadSuccess(entry, extractedFile);
                });

            } catch (Exception e) {
                e.printStackTrace();
                mainHandler.post(() -> {
                    layoutDownloadActiveControls.setVisibility(View.GONE);
                    if (activeCancelSignal.get()) {
                        txtDownloadFilename.setText("Cancelled");
                        txtDownloadStage.setText("Extraction was cancelled by user.");
                        progressDownload.setIndeterminate(false);
                        progressDownload.setProgress(0);
                        btnCloseDownloadCard.setVisibility(View.VISIBLE);
                        Toast.makeText(this, "Extraction cancelled", Toast.LENGTH_SHORT).show();
                    } else {
                        onDownloadFailure(entry, e.getMessage());
                    }
                });
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
