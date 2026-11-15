
package org.axon.noveltracker;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {

    private WebView webView;

    private static final int REQUEST_FILE = 1001;
    private static final int REQUEST_SAVE = 1002;

    private static final String UPDATE_JSON =
            "https://raw.githubusercontent.com/axon469/Novel-Tracker/main/update.json";

    private ValueCallback<Uri[]> fileCallback;
    private byte[] pendingSaveData;
    private String pendingFileName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);

        webView.addJavascriptInterface(new AndroidBridge(), "NTDB");

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(
                    WebView view,
                    ValueCallback<Uri[]> callback,
                    FileChooserParams params) {

                if (fileCallback != null) {
                    fileCallback.onReceiveValue(null);
                }

                fileCallback = callback;

                try {
                    Intent intent = params.createIntent();
                    startActivityForResult(intent, REQUEST_FILE);
                    return true;
                } catch (Exception e) {
                    fileCallback = null;
                    callback.onReceiveValue(null);
                    Toast.makeText(
                            MainActivity.this,
                            "Unable to open file picker",
                            Toast.LENGTH_SHORT
                    ).show();
                    return false;
                }
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(
                    WebView view,
                    WebResourceRequest request) {

                Uri uri = request.getUrl();

                if ("file".equals(uri.getScheme())
                        && uri.toString().startsWith(
                        "file:///android_asset/")) {
                    return false;
                }

                if ("https".equals(uri.getScheme())
                        || "http".equals(uri.getScheme())) {
                    try {
                        startActivity(new Intent(
                                Intent.ACTION_VIEW, uri));
                    } catch (Exception ignored) {
                    }
                }

                return true;
            }
        });

        webView.loadUrl("file:///android_asset/index.html");

        checkForUpdates();
    }

    private void checkForUpdates() {

        new Thread(() -> {
            HttpURLConnection connection = null;

            try {
                URL url = new URL(UPDATE_JSON);
                connection = (HttpURLConnection)
                        url.openConnection();

                connection.setRequestMethod("GET");
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                connection.setUseCaches(false);
                connection.setRequestProperty(
                        "Cache-Control", "no-cache");

                if (connection.getResponseCode() != 200) {
                    return;
                }

                InputStream input = connection.getInputStream();
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(input));

                StringBuilder response = new StringBuilder();
                String line;

                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }

                reader.close();

                JSONObject json = new JSONObject(
                        response.toString());

                int remoteVersionCode =
                        json.getInt("versionCode");

                String remoteVersionName =
                        json.optString("versionName", "New version");

                String apkUrl =
                        json.getString("apkUrl");

                String changelog =
                        json.optString(
                                "changelog",
                                "A new version is available.");

                long installedVersionCode;

                if (Build.VERSION.SDK_INT >= 28) {
                    installedVersionCode =
                            getPackageManager()
                                    .getPackageInfo(
                                            getPackageName(), 0)
                                    .getLongVersionCode();
                } else {
                    installedVersionCode =
                            getPackageManager()
                                    .getPackageInfo(
                                            getPackageName(), 0)
                                    .versionCode;
                }

                if (remoteVersionCode > installedVersionCode) {

                    runOnUiThread(() -> showUpdateDialog(
                            remoteVersionName,
                            changelog,
                            apkUrl
                    ));
                }

            } catch (Exception ignored) {
                // No popup if the update check fails.
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }

        }).start();
    }

    private void showUpdateDialog(
            String version,
            String changelog,
            String apkUrl) {

        if (isFinishing()) {
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("Update Available")
                .setMessage(
                        "Novel Tracker " + version
                                + " is available.\n\n"
                                + changelog
                )
                .setPositiveButton(
                        "Update Now",
                        (dialog, which) -> {
                            try {
                                Intent intent = new Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse(apkUrl));
                                startActivity(intent);
                            } catch (Exception e) {
                                Toast.makeText(
                                        this,
                                        "Unable to open download link",
                                        Toast.LENGTH_LONG
                                ).show();
                            }
                        }
                )
                .setNegativeButton("Update Later", null)
                .setCancelable(true)
                .show();
    }

    private class AndroidBridge {

        @JavascriptInterface
        public void save(String filename, String base64Data) {

            try {
                if (base64Data == null || base64Data.isEmpty()) {
                    return;
                }

                pendingSaveData = Base64.decode(
                        base64Data, Base64.DEFAULT);

                pendingFileName = filename;

                runOnUiThread(() -> {
                    try {
                        Intent intent = new Intent(
                                Intent.ACTION_CREATE_DOCUMENT);

                        intent.addCategory(
                                Intent.CATEGORY_OPENABLE);

                        intent.setType("application/zip");

                        intent.putExtra(
                                Intent.EXTRA_TITLE,
                                pendingFileName != null
                                        ? pendingFileName
                                        : "NovelTracker-Backup.zip");

                        startActivityForResult(
                                intent, REQUEST_SAVE);

                    } catch (Exception e) {
                        Toast.makeText(
                                MainActivity.this,
                                "Unable to save backup",
                                Toast.LENGTH_SHORT
                        ).show();
                    }
                });

            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(
                        MainActivity.this,
                        "Could not prepare backup",
                        Toast.LENGTH_SHORT
                ).show());
            }
        }
    }

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data) {

        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_FILE) {

            if (fileCallback != null) {
                Uri[] results = null;

                if (resultCode == RESULT_OK && data != null) {
                    results = WebChromeClient.FileChooserParams
                            .parseResult(resultCode, data);
                }

                fileCallback.onReceiveValue(results);
                fileCallback = null;
            }

        } else if (requestCode == REQUEST_SAVE) {

            if (resultCode == RESULT_OK
                    && data != null
                    && data.getData() != null
                    && pendingSaveData != null) {

                try {
                    Uri uri = data.getData();

                    OutputStream output = getContentResolver()
                            .openOutputStream(uri);

                    if (output != null) {
                        output.write(pendingSaveData);
                        output.flush();
                        output.close();

                        Toast.makeText(
                                this,
                                "Backup saved successfully",
                                Toast.LENGTH_LONG
                        ).show();
                    }

                } catch (Exception e) {
                    Toast.makeText(
                            this,
                            "Failed to save backup",
                            Toast.LENGTH_LONG
                    ).show();
                }
            }

            pendingSaveData = null;
            pendingFileName = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
