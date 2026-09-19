package com.example.network;

import android.content.Context;
import android.content.SharedPreferences;

public class CloudUnpackManager {

    private static final String PREF_NAME = "cloud_unpack_prefs";
    private static final String KEY_CUSTOM_WORKER_URL = "custom_worker_url";
    private static final String KEY_USE_CLOUD_BY_DEFAULT = "use_cloud_by_default";

    private final SharedPreferences prefs;

    public CloudUnpackManager(Context context) {
        this.prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public boolean isUseCloudByDefault() {
        return prefs.getBoolean(KEY_USE_CLOUD_BY_DEFAULT, false);
    }

    public void setUseCloudByDefault(boolean value) {
        prefs.edit().putBoolean(KEY_USE_CLOUD_BY_DEFAULT, value).apply();
    }

    public String getCustomWorkerUrl() {
        return prefs.getString(KEY_CUSTOM_WORKER_URL, "");
    }

    public void setCustomWorkerUrl(String url) {
        prefs.edit().putString(KEY_CUSTOM_WORKER_URL, url != null ? url.trim() : "").apply();
    }

    /**
     * Builds the Cloud Unpack URL for a specific partition inside a nested archive.
     * If a custom Cloud Worker endpoint is configured, it uses it.
     * Otherwise, if using local demo mode or direct endpoint, it points to the embedded Cloud Worker simulator.
     */
    public String buildCloudUnpackUrl(String archiveUrl, String parentEntryName, String targetFileName) {
        String customEndpoint = getCustomWorkerUrl();
        if (customEndpoint.isEmpty()) {
            // Default built-in Cloud Worker simulator endpoint on the embedded server
            customEndpoint = LocalArchiveServer.getInstance().getCloudWorkerBaseUrl();
        }

        try {
            String encodedArchive = java.net.URLEncoder.encode(archiveUrl, "UTF-8");
            String encodedParent = java.net.URLEncoder.encode(parentEntryName != null ? parentEntryName : "", "UTF-8");
            String encodedTarget = java.net.URLEncoder.encode(targetFileName, "UTF-8");

            String separator = customEndpoint.contains("?") ? "&" : "?";
            return customEndpoint + separator + "url=" + encodedArchive + "&parent=" + encodedParent + "&file=" + encodedTarget;
        } catch (Exception e) {
            return customEndpoint + "?url=" + archiveUrl + "&file=" + targetFileName;
        }
    }
}
