package com.example.parser;

import com.example.compression.Lz4Decompressor;
import com.example.model.ArchiveEntry;
import com.example.network.HttpRangeClient;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import okhttp3.Call;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Handles streaming decompression and selective extraction of entries from
 * compressed archives (such as Deflate-compressed .tar.md5 inside remote ZIPs)
 * without writing the large intermediate archive to disk.
 */
public class StreamingTarExtractor {

    private static final int BLOCK_SIZE = 512;
    private static final int BUFFER_SIZE = 64 * 1024; // 64KB memory limit

    public interface StreamExtractListener {
        void onDownloadProgress(long downloadedBytes, long totalCompressedBytes, String statusMessage);
        void onFileDiscovered(String fileName, String statusMessage);
        void onExtractProgress(long bytesWritten, long totalFileSize, String statusMessage);
    }

    private final HttpRangeClient httpClient;

    public StreamingTarExtractor(HttpRangeClient httpClient) {
        this.httpClient = httpClient;
    }

    public interface StreamScanListener {
        void onScanProgress(long downloadedBytes, long totalCompressedBytes, String statusMessage);
        void onEntryFound(int count, String fileName);
    }

    /**
     * Scans and lists the entries inside a compressed .tar.md5 file by streaming Deflate decompression
     * and reading only the 512-byte headers while skipping entry payloads without saving anything to disk.
     */
    public List<ArchiveEntry> scanTarEntriesFromCompressedStream(
            String resolvedUrl,
            ArchiveEntry parentZipEntry,
            AtomicBoolean cancelSignal,
            StreamScanListener listener
    ) throws IOException {

        RemoteZipParser zipParser = new RemoteZipParser(httpClient);
        long dataStartOffset = zipParser.resolveEntryDataOffset(resolvedUrl, parentZipEntry);
        long compressedSize = parentZipEntry.getCompressedSize();
        long dataEndOffset = dataStartOffset + compressedSize - 1;

        Response response = httpClient.fetchRangeResponse(resolvedUrl, dataStartOffset, dataEndOffset);
        ResponseBody body = response.body();
        if (body == null) {
            response.close();
            throw new IOException("Null response body for remote range request");
        }

        List<ArchiveEntry> entries = new ArrayList<>();

        try {
            InputStream netStream = body.byteStream();
            CountingInputStream countingNetStream = new CountingInputStream(netStream);

            InputStream decompressedStream;
            int method = parentZipEntry.getCompressionMethod();
            if (method == 8) { // Deflated
                decompressedStream = new InflaterInputStream(countingNetStream, new Inflater(true), BUFFER_SIZE);
            } else if (method == 0) { // Stored
                decompressedStream = countingNetStream;
            } else {
                throw new IOException("Unsupported compression method inside ZIP: " + method);
            }

            byte[] headerBlock = new byte[BLOCK_SIZE];
            byte[] skipBuffer = new byte[BUFFER_SIZE];
            long totalDecompressedBytesRead = 0;
            long parentUncompressedSize = parentZipEntry.getUncompressedSize();
            long usableTarStreamLimit = parentUncompressedSize > 64 ? (parentUncompressedSize - 34) : parentUncompressedSize;
            int consecutiveZeroBlocks = 0;

            while (usableTarStreamLimit <= 0 || totalDecompressedBytesRead + BLOCK_SIZE <= usableTarStreamLimit) {
                if (cancelSignal != null && cancelSignal.get()) {
                    throw new IOException("Scanning cancelled by user");
                }

                int headerRead = readFully(decompressedStream, headerBlock, 0, BLOCK_SIZE);
                if (headerRead < BLOCK_SIZE) break;
                totalDecompressedBytesRead += BLOCK_SIZE;

                if (isAllZeroBlock(headerBlock)) {
                    consecutiveZeroBlocks++;
                    if (consecutiveZeroBlocks >= 2) break;
                    continue;
                } else {
                    consecutiveZeroBlocks = 0;
                }

                String entryName = readTarString(headerBlock, 0, 100);
                if (entryName.isEmpty()) continue;

                long entryFileSize = readTarOctal(headerBlock, 124, 12);
                byte typeFlag = headerBlock[156];
                String prefix = readTarString(headerBlock, 345, 155);
                String fullPath = prefix.isEmpty() ? entryName : (prefix + "/" + entryName);
                boolean isDir = (typeFlag == '5') || fullPath.endsWith("/");

                ArchiveEntry entry = new ArchiveEntry(
                        fullPath,
                        entryFileSize,
                        entryFileSize,
                        -1, // Streamed - not byte-seekable directly
                        -1,
                        -1,
                        isDir,
                        "NESTED_STREAM_TAR",
                        0
                );
                entry.setParentArchiveEntry(parentZipEntry);
                entries.add(entry);

                if (listener != null) {
                    listener.onScanProgress(countingNetStream.getBytesRead(), compressedSize,
                            "Downloaded: " + ArchiveEntry.formatBytes(countingNetStream.getBytesRead()) + " (Reading headers...)");
                    listener.onEntryFound(entries.size(), fullPath);
                }

                // Skip unneeded entry data blocks in the decompressed stream
                long paddedSize = ((entryFileSize + BLOCK_SIZE - 1) / BLOCK_SIZE) * BLOCK_SIZE;
                long bytesToSkip = paddedSize;
                while (bytesToSkip > 0) {
                    if (cancelSignal != null && cancelSignal.get()) {
                        throw new IOException("Scanning cancelled by user");
                    }
                    int toRead = (int) Math.min(skipBuffer.length, bytesToSkip);
                    int read = decompressedStream.read(skipBuffer, 0, toRead);
                    if (read == -1) break;
                    bytesToSkip -= read;
                    totalDecompressedBytesRead += read;
                }
            }
        } finally {
            try { body.close(); } catch (Throwable ignored) {}
            try { response.close(); } catch (Throwable ignored) {}
        }

        return entries;
    }

    /**
     * Extracts a single target file (e.g. boot.img or boot.img.lz4) from a remote .tar.md5 entry
     * that is compressed with Deflate (or Stored) inside a ZIP archive.
     *
     * Streaming process:
     * 1. Fetches the byte range of the compressed .tar.md5 payload from the remote ZIP via HTTP Range.
     * 2. Wraps the network InputStream in an InflaterInputStream (with nowrap=true for raw Deflate).
     * 3. Streams TAR 512-byte headers in real time.
     * 4. When the matching entry is encountered, streams its bytes directly to a temporary file in destinationDirectory.
     * 5. Immediately aborts/closes the network stream and connection.
     * 6. Decompresses LZ4 payload if applicable.
     */
    public File extractSingleEntryFromCompressedTar(
            String resolvedUrl,
            ArchiveEntry parentZipEntry,
            String targetFileName,
            File destinationDirectory,
            AtomicBoolean cancelSignal,
            StreamExtractListener listener
    ) throws IOException {

        // Resolve byte offsets of the compressed parent entry in the remote ZIP
        RemoteZipParser zipParser = new RemoteZipParser(httpClient);
        long dataStartOffset = zipParser.resolveEntryDataOffset(resolvedUrl, parentZipEntry);
        long compressedSize = parentZipEntry.getCompressedSize();
        long dataEndOffset = dataStartOffset + compressedSize - 1;

        if (cancelSignal != null && cancelSignal.get()) {
            throw new IOException("Extraction cancelled by user");
        }

        if (listener != null) {
            listener.onDownloadProgress(0, compressedSize,
                    "Connecting to remote byte range for " + parentZipEntry.getSimpleFileName() + " (" +
                            ArchiveEntry.formatBytes(compressedSize) + ")...");
        }

        // Call fetchRangeResponse
        Call[] activeCallHolder = new Call[1];
        Response response = httpClient.fetchRangeResponse(resolvedUrl, dataStartOffset, dataEndOffset);
        ResponseBody body = response.body();
        if (body == null) {
            response.close();
            throw new IOException("Null response body for remote range request");
        }

        File tempFile = null;
        File extractedFile = null;
        boolean isSuccess = false;

        try {
            InputStream netStream = body.byteStream();
            CountingInputStream countingNetStream = new CountingInputStream(netStream);

            InputStream decompressedStream;
            int method = parentZipEntry.getCompressionMethod();
            if (method == 8) { // Deflated
                // ZIP Deflate uses raw deflate without zlib header/checksum -> nowrap = true
                decompressedStream = new InflaterInputStream(countingNetStream, new Inflater(true), BUFFER_SIZE);
            } else if (method == 0) { // Stored (uncompressed)
                decompressedStream = countingNetStream;
            } else {
                throw new IOException("Unsupported compression method inside ZIP: " + method);
            }

            byte[] headerBlock = new byte[BLOCK_SIZE];
            byte[] skipBuffer = new byte[BUFFER_SIZE];
            byte[] copyBuffer = new byte[BUFFER_SIZE];

            long totalDecompressedBytesRead = 0;
            long parentUncompressedSize = parentZipEntry.getUncompressedSize();
            // Handle .tar.md5 trailing md5 trailer (32-34 bytes)
            long usableTarStreamLimit = parentUncompressedSize > 64 ? (parentUncompressedSize - 34) : parentUncompressedSize;

            int consecutiveZeroBlocks = 0;
            boolean fileFound = false;

            while (usableTarStreamLimit <= 0 || totalDecompressedBytesRead + BLOCK_SIZE <= usableTarStreamLimit) {
                if (cancelSignal != null && cancelSignal.get()) {
                    throw new IOException("Extraction cancelled by user");
                }

                // Read exactly 512 bytes for TAR header
                int headerRead = readFully(decompressedStream, headerBlock, 0, BLOCK_SIZE);
                if (headerRead < BLOCK_SIZE) {
                    break;
                }
                totalDecompressedBytesRead += BLOCK_SIZE;

                // Check for zero block (end of TAR)
                boolean isZero = isAllZeroBlock(headerBlock);
                if (isZero) {
                    consecutiveZeroBlocks++;
                    if (consecutiveZeroBlocks >= 2) {
                        break;
                    }
                    continue;
                } else {
                    consecutiveZeroBlocks = 0;
                }

                // Parse TAR header
                String entryName = readTarString(headerBlock, 0, 100);
                if (entryName.isEmpty()) {
                    continue;
                }

                long entryFileSize = readTarOctal(headerBlock, 124, 12);
                byte typeFlag = headerBlock[156];
                String prefix = readTarString(headerBlock, 345, 155);
                String fullPath = prefix.isEmpty() ? entryName : (prefix + "/" + entryName);

                String simpleName = fullPath.contains("/") ? fullPath.substring(fullPath.lastIndexOf('/') + 1) : fullPath;
                boolean isDir = (typeFlag == '5') || fullPath.endsWith("/");

                long downloadedSoFar = countingNetStream.getBytesRead();
                String progMsg = "Downloaded: " + ArchiveEntry.formatBytes(downloadedSoFar) +
                        (compressedSize > 0 ? " / " + ArchiveEntry.formatBytes(compressedSize) : "");

                if (listener != null) {
                    listener.onDownloadProgress(downloadedSoFar, compressedSize, progMsg);
                    listener.onFileDiscovered(simpleName, "Found: " + simpleName + " ... Looking for " + targetFileName);
                }

                // Determine 512-byte aligned data size
                long paddedSize = ((entryFileSize + BLOCK_SIZE - 1) / BLOCK_SIZE) * BLOCK_SIZE;

                // Check if this matches target
                if (!isDir && matchesTargetName(simpleName, targetFileName)) {
                    fileFound = true;
                    if (listener != null) {
                        listener.onFileDiscovered(simpleName, "Extracting " + simpleName + " (" + ArchiveEntry.formatBytes(entryFileSize) + ") ...");
                    }

                    tempFile = new File(destinationDirectory, simpleName + ".part");
                    extractedFile = new File(destinationDirectory, simpleName);

                    try (OutputStream fos = new BufferedOutputStream(new FileOutputStream(tempFile), BUFFER_SIZE)) {
                        long remainingData = entryFileSize;
                        long fileBytesWritten = 0;

                        while (remainingData > 0) {
                            if (cancelSignal != null && cancelSignal.get()) {
                                throw new IOException("Extraction cancelled by user");
                            }

                            int toRead = (int) Math.min(copyBuffer.length, remainingData);
                            int read = decompressedStream.read(copyBuffer, 0, toRead);
                            if (read == -1) {
                                throw new IOException("Unexpected EOF while streaming target entry data");
                            }
                            fos.write(copyBuffer, 0, read);
                            remainingData -= read;
                            fileBytesWritten += read;

                            if (listener != null) {
                                listener.onExtractProgress(fileBytesWritten, entryFileSize,
                                        "Extracting " + simpleName + " (" + ArchiveEntry.formatBytes(fileBytesWritten) +
                                                " / " + ArchiveEntry.formatBytes(entryFileSize) + ") ...");
                            }
                        }
                        fos.flush();
                    }

                    // Success! Target extracted. Immediately break and terminate connection!
                    isSuccess = true;
                    break;
                } else {
                    // Not the target file: skip its data blocks to advance the stream
                    long bytesToSkip = paddedSize;
                    while (bytesToSkip > 0) {
                        if (cancelSignal != null && cancelSignal.get()) {
                            throw new IOException("Extraction cancelled by user");
                        }

                        int toRead = (int) Math.min(skipBuffer.length, bytesToSkip);
                        int read = decompressedStream.read(skipBuffer, 0, toRead);
                        if (read == -1) {
                            break;
                        }
                        bytesToSkip -= read;
                        totalDecompressedBytesRead += read;
                    }
                }
            }

            if (!fileFound) {
                throw new IOException("Entry matching '" + targetFileName + "' was not found inside " + parentZipEntry.getSimpleFileName());
            }

        } finally {
            // Close HTTP connection immediately
            try {
                body.close();
            } catch (Throwable ignored) {}
            try {
                response.close();
            } catch (Throwable ignored) {}
        }

        if (!isSuccess || tempFile == null || !tempFile.exists()) {
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
            throw new IOException("Failed to extract target file " + targetFileName);
        }

        // Rename part file to final file
        if (extractedFile.exists()) {
            extractedFile.delete();
        }
        if (!tempFile.renameTo(extractedFile)) {
            copyFile(tempFile, extractedFile);
            tempFile.delete();
        }

        // Check if extracted file is an LZ4 archive (e.g. boot.img.lz4)
        boolean isLz4 = extractedFile.getName().toLowerCase(Locale.ROOT).endsWith(".lz4")
                || Lz4Decompressor.isLz4File(extractedFile);

        if (isLz4) {
            if (listener != null) {
                listener.onExtractProgress(0, 0, "Decompressing LZ4 image to raw partition...");
            }
            String decompName = extractedFile.getName();
            if (decompName.toLowerCase(Locale.ROOT).endsWith(".lz4")) {
                decompName = decompName.substring(0, decompName.length() - 4);
            } else {
                decompName = decompName + ".img";
            }

            File decompressedFinal = new File(destinationDirectory, decompName);
            try {
                boolean decompSuccess = Lz4Decompressor.decompressLz4File(extractedFile, decompressedFinal);
                if (decompSuccess && decompressedFinal.exists() && decompressedFinal.length() > 0) {
                    // Optional: keep raw partition file as the extracted outcome
                    return decompressedFinal;
                }
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }

        return extractedFile;
    }

    /**
     * Helper to check if a TAR entry name matches the requested target (supports matching
     * boot.img when target is boot.img or boot.img.lz4).
     */
    private static boolean matchesTargetName(String simpleName, String targetFileName) {
        if (simpleName == null || targetFileName == null) return false;
        if (simpleName.equalsIgnoreCase(targetFileName)) return true;

        String s1 = simpleName.toLowerCase(Locale.ROOT);
        String t1 = targetFileName.toLowerCase(Locale.ROOT);

        // Strip trailing .lz4
        String sClean = s1.endsWith(".lz4") ? s1.substring(0, s1.length() - 4) : s1;
        String tClean = t1.endsWith(".lz4") ? t1.substring(0, t1.length() - 4) : t1;

        if (sClean.equalsIgnoreCase(tClean)) return true;

        // Strip extensions for partition name comparison (e.g. boot.img vs boot.img.lz4 or boot)
        return false;
    }

    private static int readFully(InputStream in, byte[] b, int off, int len) throws IOException {
        int total = 0;
        while (total < len) {
            int count = in.read(b, off + total, len - total);
            if (count < 0) {
                break;
            }
            total += count;
        }
        return total;
    }

    private static boolean isAllZeroBlock(byte[] block) {
        for (byte b : block) {
            if (b != 0) return false;
        }
        return true;
    }

    private static String readTarString(byte[] buf, int offset, int length) {
        int end = offset;
        int max = offset + length;
        while (end < max && buf[end] != 0) {
            end++;
        }
        return new String(buf, offset, end - offset, StandardCharsets.UTF_8).trim();
    }

    private static long readTarOctal(byte[] buf, int offset, int length) {
        long result = 0;
        int end = offset + length;
        int start = offset;
        while (start < end && (buf[start] == ' ' || buf[start] == 0)) {
            start++;
        }
        while (start < end && buf[start] >= '0' && buf[start] <= '7') {
            result = (result << 3) + (buf[start] - '0');
            start++;
        }
        return result;
    }

    private static void copyFile(File src, File dst) throws IOException {
        try (InputStream in = new java.io.FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[BUFFER_SIZE];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        }
    }

    /**
     * InputStream decorator that tracks total bytes read from the underlying network stream.
     */
    public static class CountingInputStream extends InputStream {
        private final InputStream delegate;
        private long bytesRead = 0;

        public CountingInputStream(InputStream delegate) {
            this.delegate = delegate;
        }

        public long getBytesRead() {
            return bytesRead;
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b != -1) {
                bytesRead++;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int count = delegate.read(b, off, len);
            if (count != -1) {
                bytesRead += count;
            }
            return count;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = delegate.skip(n);
            if (skipped > 0) {
                bytesRead += skipped;
            }
            return skipped;
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
