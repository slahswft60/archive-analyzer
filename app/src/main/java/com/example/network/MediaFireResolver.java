package com.example.network;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Resolves direct download links for MediaFire sharing URLs.
 * MediaFire URLs are typically in the format:
 * - https://www.mediafire.com/file/KEY/FILENAME/file
 * - https://www.mediafire.com/file/KEY/FILENAME
 * - https://www.mediafire.com/download/KEY
 * 
 * MediaFire html pages embed the direct download URL in an anchor tag with id="downloadButton"
 * or in aria-label="Download file", or JavaScript window.location.href.
 */
public class MediaFireResolver {

    private static final Pattern MEDIAFIRE_PAGE_PATTERN = Pattern.compile(
            "https?://(?:www\\.)?mediafire\\.com/(?:file|download)/([a-zA-Z0-9]+)(?:/[^/\\s\"']*)?"
    );

    // Common HTML patterns on MediaFire download page
    private static final Pattern DOWNLOAD_LINK_PATTERN_1 = Pattern.compile(
            "href=[\"'](https?://download[0-9]*\\.mediafire\\.com/[^\"']+)[\"']"
    );

    private static final Pattern DOWNLOAD_LINK_PATTERN_2 = Pattern.compile(
            "id=[\"']downloadButton[\"'][^>]*href=[\"']([^\"']+)[\"']"
    );

    private static final Pattern DOWNLOAD_LINK_PATTERN_3 = Pattern.compile(
            "aria-label=[\"']Download file[\"'][^>]*href=[\"']([^\"']+)[\"']"
    );

    private static final Pattern DOWNLOAD_LINK_PATTERN_4 = Pattern.compile(
            "href=[\"'](https?://[^\"']+mediafire\\.com/dynamic/download\\.php[^\"']+)[\"']"
    );

    public static boolean isMediaFireUrl(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        return lower.contains("mediafire.com/file/") || lower.contains("mediafire.com/download/");
    }

    /**
     * Resolves the direct download URL from a MediaFire page URL using the provided OkHttpClient.
     * If resolution fails or it's not a MediaFire URL, it returns the original URL.
     */
    public static String resolveDirectLink(OkHttpClient client, String originalUrl, String userAgent) throws IOException {
        if (!isMediaFireUrl(originalUrl)) {
            return originalUrl;
        }

        Request pageRequest = new Request.Builder()
                .url(originalUrl)
                .header("User-Agent", userAgent != null ? userAgent : "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.5")
                .build();

        try (Response response = client.newCall(pageRequest).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Failed to fetch MediaFire page (HTTP " + response.code() + ")");
            }

            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("Empty response body from MediaFire page");
            }

            String html = body.string();

            // 1. Check direct download link pattern (download*.mediafire.com)
            Matcher m1 = DOWNLOAD_LINK_PATTERN_1.matcher(html);
            if (m1.find()) {
                return cleanResolvedUrl(m1.group(1));
            }

            // 2. Check id="downloadButton"
            Matcher m2 = DOWNLOAD_LINK_PATTERN_2.matcher(html);
            if (m2.find()) {
                return cleanResolvedUrl(m2.group(1));
            }

            // 3. Check aria-label="Download file"
            Matcher m3 = DOWNLOAD_LINK_PATTERN_3.matcher(html);
            if (m3.find()) {
                return cleanResolvedUrl(m3.group(1));
            }

            // 4. Check dynamic download link
            Matcher m4 = DOWNLOAD_LINK_PATTERN_4.matcher(html);
            if (m4.find()) {
                return cleanResolvedUrl(m4.group(1));
            }
        }

        // If none of the regex patterns matched, return original so standard probe can try
        return originalUrl;
    }

    private static String cleanResolvedUrl(String rawUrl) {
        if (rawUrl == null) return "";
        return rawUrl.replace("&amp;", "&").trim();
    }
}
