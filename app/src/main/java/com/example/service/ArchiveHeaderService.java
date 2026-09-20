package com.example.service;

import com.example.model.ArchiveEntry;
import com.example.network.HttpRangeClient;
import com.example.parser.RemoteTarParser;
import com.example.parser.RemoteZipParser;
import com.example.parser.StreamingTarExtractor;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Service layer responsible for parsing ZIP and TAR file headers using OkHttp HTTP Range requests.
 * This service enables the app to inspect remote archives and list all contained files
 * by downloading only minimal metadata headers (EOCD/Central Directory for ZIP, and 512-byte header blocks for TAR)
 * without downloading the full archive file.
 */
public class ArchiveHeaderService {

    private final HttpRangeClient httpClient;
    private final RemoteZipParser zipParser;
    private final RemoteTarParser tarParser;
    private final StreamingTarExtractor streamingTarExtractor;

    public interface ParseProgressListener {
        void onProgress(String stage, String details);
    }

    public interface EntryDownloadListener {
        void onProgress(long bytesRead, long totalBytes, String stageMessage);
    }

    public ArchiveHeaderService(HttpRangeClient httpClient) {
        this.httpClient = httpClient != null ? httpClient : new HttpRangeClient();
        this.zipParser = new RemoteZipParser(this.httpClient);
        this.tarParser = new RemoteTarParser(this.httpClient);
        this.streamingTarExtractor = new StreamingTarExtractor(this.httpClient);
    }

    public ArchiveHeaderService() {
        this(new HttpRangeClient());
    }

    public HttpRangeClient getHttpClient() {
        return httpClient;
    }

    public RemoteZipParser getZipParser() {
        return zipParser;
    }

    public RemoteTarParser getTarParser() {
        return tarParser;
    }

    public StreamingTarExtractor getStreamingTarExtractor() {
        return streamingTarExtractor;
    }

    /**
     * Probes and parses a remote archive (ZIP or TAR) by inspecting headers with OkHttp range requests.
     *
     * @param url Direct archive URL (or supported sharing URLs like MediaFire/Google Drive)
     * @param listener Optional progress callback
     * @return Result containing archive type, file size, range capability, and parsed entries
     * @throws IOException on network or archive parsing error
     */
    public ArchiveParseResult parseRemoteArchive(String url, ParseProgressListener listener) throws IOException {
        if (listener != null) {
            listener.onProgress("Connecting to remote archive...", "Checking HTTP Range request support...");
        }

        HttpRangeClient.RemoteFileInfo fileInfo = httpClient.probeRemoteFile(url);
        long totalSize = fileInfo.getContentLength();
        String resolvedUrl = fileInfo.getResolvedUrl();
        String originalUrl = fileInfo.getOriginalUrl();

        if (listener != null) {
            listener.onProgress("Probing archive headers...", "Total archive size: " +
                    ArchiveEntry.formatBytes(totalSize) + " (reading headers only)");
        }

        String urlLower = (resolvedUrl != null ? resolvedUrl : url).toLowerCase(Locale.ROOT);
        boolean isTarHint = urlLower.contains(".tar");

        List<ArchiveEntry> entries = null;
        ArchiveParseResult.ArchiveType detectedType = ArchiveParseResult.ArchiveType.UNKNOWN;

        if (isTarHint) {
            try {
                if (listener != null) {
                    listener.onProgress("Reading TAR header blocks...", "Inspecting 512-byte headers via HTTP Range...");
                }
                entries = tarParser.parseTarArchive(resolvedUrl, totalSize);
                detectedType = ArchiveParseResult.ArchiveType.TAR;
            } catch (Exception tarEx) {
                // Fallback to ZIP central directory
                if (listener != null) {
                    listener.onProgress("Inspecting ZIP Central Directory...", "Attempting ZIP EOCD header parsing...");
                }
                entries = zipParser.parseCentralDirectory(resolvedUrl, totalSize);
                detectedType = ArchiveParseResult.ArchiveType.ZIP;
            }
        } else {
            try {
                if (listener != null) {
                    listener.onProgress("Parsing ZIP Central Directory...", "Fetching EOCD and CDFH headers via Range...");
                }
                entries = zipParser.parseCentralDirectory(resolvedUrl, totalSize);
                detectedType = ArchiveParseResult.ArchiveType.ZIP;
            } catch (Exception zipEx) {
                // Fallback to TAR headers
                if (listener != null) {
                    listener.onProgress("Parsing TAR headers...", "Checking for TAR blocks via Range...");
                }
                entries = tarParser.parseTarArchive(resolvedUrl, totalSize);
                detectedType = ArchiveParseResult.ArchiveType.TAR;
            }
        }

        return new ArchiveParseResult(
                detectedType,
                originalUrl,
                resolvedUrl,
                totalSize,
                fileInfo.isSupportsRange(),
                entries
        );
    }

    /**
     * Parses a nested TAR archive (e.g. AP_*.tar.md5 inside a ZIP archive) without downloading the full archive.
     * If stored (uncompressed), reads 512-byte headers via OkHttp range requests.
     * If compressed with Deflate, stream-decompresses the catalog in memory without writing the multi-gigabyte container to disk.
     *
     * @param resolvedUrl Remote archive URL
     * @param tarEntry Parent entry representing the TAR file
     * @param isParentTar Whether the enclosing archive is a TAR or ZIP
     * @param cancelSignal Atomic boolean to allow aborting the scan
     * @param listener Progress listener
     * @return List of entries contained within the nested TAR
     * @throws IOException on error
     */
    public List<ArchiveEntry> parseNestedTar(
            String resolvedUrl,
            ArchiveEntry tarEntry,
            boolean isParentTar,
            AtomicBoolean cancelSignal,
            ParseProgressListener listener) throws IOException {

        long dataStartOffset;
        if (isParentTar) {
            dataStartOffset = tarEntry.getDataOffset();
            if (dataStartOffset <= 0) {
                dataStartOffset = tarEntry.getHeaderOffset() + 512;
            }
        } else {
            dataStartOffset = zipParser.resolveEntryDataOffset(resolvedUrl, tarEntry);
        }

        if (tarEntry.getCompressionMethod() == 8) {
            // Compressed with Deflate: stream-decompress in real time without disk persistence
            if (listener != null) {
                listener.onProgress("Scanning compressed TAR stream...",
                        "Decompressing Deflate stream in real time (headers only, 0 MB saved to disk)...");
            }
            return streamingTarExtractor.scanTarEntriesFromCompressedStream(
                    resolvedUrl,
                    tarEntry,
                    cancelSignal != null ? cancelSignal : new AtomicBoolean(false),
                    new StreamingTarExtractor.StreamScanListener() {
                        @Override
                        public void onScanProgress(long downloadedBytes, long totalCompressedBytes, String statusMessage) {
                            if (listener != null) {
                                listener.onProgress("Scanning compressed TAR...", statusMessage);
                            }
                        }

                        @Override
                        public void onEntryFound(int count, String fileName) {
                            if (listener != null) {
                                listener.onProgress("Scanning compressed TAR...", "Found: " + fileName + " (" + count + " items)...");
                            }
                        }
                    }
            );
        } else {
            // Stored uncompressed: read headers directly using OkHttp range requests
            boolean isValidTar = tarParser.verifyTarHeader(resolvedUrl, dataStartOffset);
            if (!isValidTar) {
                throw new IOException("The entry " + tarEntry.getSimpleFileName() +
                        " does not contain a standard TAR/ustar header at byte offset " + dataStartOffset);
            }

            if (listener != null) {
                listener.onProgress("Reading TAR header blocks...", "Parsing nested headers using 64KB HTTP Range requests...");
            }
            long tarLength = tarEntry.getUncompressedSize();
            return tarParser.parseTarAtOffset(resolvedUrl, dataStartOffset, tarLength,
                    (count, currentFileName, currentOffset) -> {
                        if (listener != null) {
                            listener.onProgress("Reading TAR headers...", "Found " + count + " partitions: " + currentFileName);
                        }
                    });
        }
    }

    /**
     * Downloads and extracts an individual entry using HTTP Range requests without downloading the full archive.
     */
    public File extractEntry(
            String resolvedUrl,
            ArchiveEntry entry,
            boolean isTarArchive,
            File destinationDir,
            EntryDownloadListener progressListener) throws IOException {

        HttpRangeClient.ProgressListener listener = progressListener != null
                ? progressListener::onProgress
                : null;

        if (isTarArchive) {
            return tarParser.downloadAndExtractEntry(resolvedUrl, entry, destinationDir, listener);
        } else {
            return zipParser.downloadAndExtractEntry(resolvedUrl, entry, destinationDir, listener);
        }
    }
}
