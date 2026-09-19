package com.example.parser;

import com.example.compression.Lz4Decompressor;
import com.example.model.ArchiveEntry;
import com.example.network.HttpRangeClient;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import okhttp3.Response;
import okhttp3.ResponseBody;

public class RemoteTarParser {

    private static final int BLOCK_SIZE = 512;
    private static final int BUFFER_CHUNK_SIZE = 64 * 1024;

    private final HttpRangeClient httpClient;

    public RemoteTarParser(HttpRangeClient httpClient) {
        this.httpClient = httpClient;
    }

    public List<ArchiveEntry> parseTarArchive(String resolvedUrl, long totalFileSize) throws IOException {
        List<ArchiveEntry> entries = new ArrayList<>();
        long currentOffset = 0;

        byte[] currentBuffer = null;
        long bufferStartOffset = -1;
        long bufferEndOffset = -1;
        int consecutiveZeroBlocks = 0;

        while (currentOffset + BLOCK_SIZE <= totalFileSize) {
            // Ensure 512-byte header at currentOffset is loaded
            if (currentBuffer == null || currentOffset < bufferStartOffset || currentOffset + BLOCK_SIZE > bufferEndOffset) {
                bufferStartOffset = currentOffset;
                long fetchEnd = Math.min(currentOffset + BUFFER_CHUNK_SIZE - 1, totalFileSize - 1);
                currentBuffer = httpClient.fetchRange(resolvedUrl, bufferStartOffset, fetchEnd);
                bufferEndOffset = bufferStartOffset + currentBuffer.length;
            }

            int offsetInBuffer = (int) (currentOffset - bufferStartOffset);
            if (offsetInBuffer + BLOCK_SIZE > currentBuffer.length) {
                break;
            }

            // Check for zero block
            boolean isAllZero = true;
            for (int i = 0; i < BLOCK_SIZE; i++) {
                if (currentBuffer[offsetInBuffer + i] != 0) {
                    isAllZero = false;
                    break;
                }
            }

            if (isAllZero) {
                consecutiveZeroBlocks++;
                if (consecutiveZeroBlocks >= 2) {
                    // Standard TAR end of archive marker
                    break;
                }
                currentOffset += BLOCK_SIZE;
                continue;
            } else {
                consecutiveZeroBlocks = 0;
            }

            // Parse TAR header
            String rawName = readString(currentBuffer, offsetInBuffer, 100);
            if (rawName.isEmpty()) {
                currentOffset += BLOCK_SIZE;
                continue;
            }

            long fileSize = readOctal(currentBuffer, offsetInBuffer + 124, 12);
            byte typeFlag = currentBuffer[offsetInBuffer + 156];
            String magic = readString(currentBuffer, offsetInBuffer + 257, 6);
            String prefix = readString(currentBuffer, offsetInBuffer + 345, 155);

            String fullPath = rawName;
            if (!prefix.isEmpty()) {
                fullPath = prefix + "/" + rawName;
            }

            boolean isDirectory = (typeFlag == '5') || fullPath.endsWith("/");

            long dataOffset = currentOffset + BLOCK_SIZE;
            entries.add(new ArchiveEntry(
                    fullPath,
                    fileSize,
                    fileSize,
                    currentOffset,
                    dataOffset,
                    -1,
                    isDirectory,
                    "TAR",
                    0
            ));

            // Calculate next entry header offset
            // In TAR, file data is padded to 512-byte boundaries
            long paddedDataSize = ((fileSize + BLOCK_SIZE - 1) / BLOCK_SIZE) * BLOCK_SIZE;
            currentOffset += BLOCK_SIZE + paddedDataSize;
        }

        return entries;
    }

    public File downloadAndExtractEntry(String resolvedUrl, ArchiveEntry entry,
                                       File destinationDirectory, HttpRangeClient.ProgressListener progressListener)
            throws IOException {
        long dataOffset = entry.getDataOffset();
        if (dataOffset <= 0) {
            dataOffset = entry.getHeaderOffset() + BLOCK_SIZE;
            entry.setDataOffset(dataOffset);
        }

        long fileSize = entry.getUncompressedSize();
        long endOffset = dataOffset + fileSize - 1;

        String targetFileName = entry.getSimpleFileName();
        File tempDownloadedFile = new File(destinationDirectory, targetFileName + ".part");
        File finalExtractedFile = new File(destinationDirectory, targetFileName);

        if (progressListener != null) {
            progressListener.onProgress(0, fileSize, "Downloading TAR byte range (" + ArchiveEntry.formatBytes(fileSize) + ")...");
        }

        if (fileSize == 0) {
            finalExtractedFile.createNewFile();
            return finalExtractedFile;
        }

        try (Response response = httpClient.fetchRangeResponse(resolvedUrl, dataOffset, endOffset)) {
            ResponseBody body = response.body();
            if (body == null) throw new IOException("Empty response body for TAR entry");

            try (InputStream netStream = body.byteStream();
                 BufferedOutputStream fos = new BufferedOutputStream(new FileOutputStream(tempDownloadedFile))) {

                byte[] buffer = new byte[64 * 1024];
                long totalRead = 0;
                int read;
                while ((read = netStream.read(buffer)) != -1) {
                    fos.write(buffer, 0, read);
                    totalRead += read;
                    if (progressListener != null) {
                        progressListener.onProgress(totalRead, fileSize, "Writing extracted file data...");
                    }
                }
                fos.flush();
            }
        }

        if (finalExtractedFile.exists()) {
            finalExtractedFile.delete();
        }
        if (!tempDownloadedFile.renameTo(finalExtractedFile)) {
            copyFile(tempDownloadedFile, finalExtractedFile);
            tempDownloadedFile.delete();
        }

        // Decompress LZ4 if needed
        if (entry.isLz4() || Lz4Decompressor.isLz4File(finalExtractedFile)) {
            if (progressListener != null) {
                progressListener.onProgress(0, 0, "Decompressing LZ4 payload...");
            }
            String decompressedName = entry.getDecompressedTargetName();
            File decompressedFile = new File(destinationDirectory, decompressedName);
            try {
                boolean success = Lz4Decompressor.decompressLz4File(finalExtractedFile, decompressedFile);
                if (success && decompressedFile.exists() && decompressedFile.length() > 0) {
                    return decompressedFile;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        return finalExtractedFile;
    }

    private static String readString(byte[] buf, int offset, int length) {
        int end = offset;
        int max = offset + length;
        while (end < max && buf[end] != 0) {
            end++;
        }
        return new String(buf, offset, end - offset, StandardCharsets.UTF_8).trim();
    }

    private static long readOctal(byte[] buf, int offset, int length) {
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

    private void copyFile(File src, File dst) throws IOException {
        try (InputStream in = new java.io.FileInputStream(src);
             java.io.OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[32 * 1024];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        }
    }
}
