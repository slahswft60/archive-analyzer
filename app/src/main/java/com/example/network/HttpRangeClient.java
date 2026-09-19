package com.example.network;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class HttpRangeClient {

    private static final String USER_AGENT = "Mozilla/5.0 (Android; RemoteArchiveAnalyzer/1.0)";
    private final OkHttpClient client;

    public interface ProgressListener {
        void onProgress(long bytesRead, long totalBytes, String stageMessage);
    }

    public static class RemoteFileInfo {
        private final String originalUrl;
        private final String resolvedUrl;
        private final long contentLength;
        private final boolean supportsRange;

        public RemoteFileInfo(String originalUrl, String resolvedUrl, long contentLength, boolean supportsRange) {
            this.originalUrl = originalUrl;
            this.resolvedUrl = resolvedUrl;
            this.contentLength = contentLength;
            this.supportsRange = supportsRange;
        }

        public String getOriginalUrl() {
            return originalUrl;
        }

        public String getResolvedUrl() {
            return resolvedUrl;
        }

        public long getContentLength() {
            return contentLength;
        }

        public boolean isSupportsRange() {
            return supportsRange;
        }
    }

    public HttpRangeClient() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build();
    }

    public static String normalizeUrl(String inputUrl) {
        if (inputUrl == null) return "";
        String url = inputUrl.trim();

        // Handle Google Drive links
        // e.g. https://drive.google.com/file/d/1A2B3C4D.../view?usp=sharing
        Pattern gdriveFilePattern = Pattern.compile("drive\\.google\\.com/file/d/([a-zA-Z0-9_-]+)");
        Matcher m1 = gdriveFilePattern.matcher(url);
        if (m1.find()) {
            String fileId = m1.group(1);
            return "https://drive.usercontent.google.com/download?id=" + fileId + "&export=download&confirm=t";
        }

        // e.g. https://drive.google.com/open?id=1A2B3C4D...
        Pattern gdriveIdPattern = Pattern.compile("drive\\.google\\.com/open\\?id=([a-zA-Z0-9_-]+)");
        Matcher m2 = gdriveIdPattern.matcher(url);
        if (m2.find()) {
            String fileId = m2.group(1);
            return "https://drive.usercontent.google.com/download?id=" + fileId + "&export=download&confirm=t";
        }

        // Handle Dropbox links
        if (url.contains("dropbox.com") && url.contains("dl=0")) {
            url = url.replace("dl=0", "dl=1");
        }

        return url;
    }

    public RemoteFileInfo probeRemoteFile(String rawUrl) throws IOException {
        String normalized = normalizeUrl(rawUrl);

        // Check if URL is MediaFire sharing link and resolve to direct CDN file link
        if (MediaFireResolver.isMediaFireUrl(normalized)) {
            try {
                String resolvedMediaFire = MediaFireResolver.resolveDirectLink(client, normalized, USER_AGENT);
                if (resolvedMediaFire != null && !resolvedMediaFire.isEmpty() && !resolvedMediaFire.equals(normalized)) {
                    normalized = resolvedMediaFire;
                }
            } catch (Exception ignored) {
                // Fall back to probing directly
            }
        }

        // Try HTTP Range bytes=0-0 first to verify range support and get total size
        Request testRangeRequest = new Request.Builder()
                .url(normalized)
                .header("User-Agent", USER_AGENT)
                .header("Range", "bytes=0-0")
                .build();

        Call call = client.newCall(testRangeRequest);
        try (Response response = call.execute()) {
            String finalUrl = response.request().url().toString();
            int code = response.code();

            long totalLength = -1;
            boolean supportsRange = false;

            String contentRange = response.header("Content-Range");
            if (contentRange != null) {
                // Format: bytes 0-0/12345678 or bytes 0-0/*
                int slash = contentRange.lastIndexOf('/');
                if (slash != -1) {
                    String totalStr = contentRange.substring(slash + 1).trim();
                    try {
                        totalLength = Long.parseLong(totalStr);
                        supportsRange = true;
                    } catch (NumberFormatException ignored) {
                    }
                }
            }

            if (code == 206) {
                supportsRange = true;
            }

            if (totalLength <= 0) {
                String acceptRanges = response.header("Accept-Ranges");
                if ("bytes".equalsIgnoreCase(acceptRanges)) {
                    supportsRange = true;
                }
                String cl = response.header("Content-Length");
                if (cl != null) {
                    try {
                        long val = Long.parseLong(cl);
                        if (code == 200) {
                            totalLength = val;
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            }

            // If range probe returned 200 instead of 206, server might not support Range, or sent whole file.
            // Let's do a fallback HEAD request if totalLength is still unknown
            if (totalLength <= 0) {
                Request headReq = new Request.Builder()
                        .url(finalUrl)
                        .header("User-Agent", USER_AGENT)
                        .head()
                        .build();
                try (Response headResp = client.newCall(headReq).execute()) {
                    String cl = headResp.header("Content-Length");
                    if (cl != null) {
                        try {
                            totalLength = Long.parseLong(cl);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    String ar = headResp.header("Accept-Ranges");
                    if ("bytes".equalsIgnoreCase(ar)) {
                        supportsRange = true;
                    }
                }
            }

            if (totalLength <= 0) {
                throw new IOException("Unable to determine remote file size. The server may not support HTTP Range requests or Content-Length.");
            }

            return new RemoteFileInfo(rawUrl, finalUrl, totalLength, supportsRange);
        }
    }

    public byte[] fetchRange(String url, long startByte, long endByte) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Range", "bytes=" + startByte + "-" + endByte)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP range request failed with code " + response.code() + ": " + response.message());
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("Empty response body for range " + startByte + "-" + endByte);
            }
            return body.bytes();
        }
    }

    public Response fetchRangeResponse(String url, long startByte, long endByte) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Range", "bytes=" + startByte + "-" + endByte)
                .build();

        Response response = client.newCall(request).execute();
        if (!response.isSuccessful()) {
            response.close();
            throw new IOException("HTTP range request failed with code " + response.code() + ": " + response.message());
        }
        return response;
    }
}
