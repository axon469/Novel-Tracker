
package org.axon.noveltracker;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.text.Html;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.view.ViewGroup;
import android.view.Gravity;
import android.graphics.Color;
import android.widget.Button;

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

    private static final int DARK_BG = Color.rgb(23, 25, 28);
    private static final int DARK_CARD = Color.rgb(30, 33, 37);
    private static final int TEXT_PRIMARY = Color.rgb(241, 243, 245);
    private static final int TEXT_SECONDARY = Color.rgb(157, 165, 174);
    private static final int TEXT_BODY = Color.rgb(205, 210, 216);
    private static final int ACCENT = Color.rgb(64, 190, 169);

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

    // ==========================================
    // UPDATE CHECKER
    // ==========================================

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

    // ==========================================
    // DARK UPDATE POPUP
    // ==========================================

    private int dp(float value) {
        return (int) (
                value * getResources()
                        .getDisplayMetrics().density + 0.5f
        );
    }

    private GradientDrawable roundedBackground(
            int color,
            float radius) {

        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private String escapeHtml(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private String formatChangelog(String markdown) {

        StringBuilder html = new StringBuilder();
        String[] lines = markdown.split("\\n");

        for (String originalLine : lines) {

            String line = originalLine.trim();

            if (line.isEmpty()) {
                continue;
            }

            line = escapeHtml(line);

            line = line.replaceAll(
                    "\\*\\*(.*?)\\*\\*",
                    "<b>$1</b>"
            );

            if (line.startsWith("### ")) {

                html.append(
                        "<br><font color='#AEB8C2'><b>"
                ).append(line.substring(4))
                 .append("</b></font><br>");

            } else if (line.startsWith("## ")) {

                html.append(
                        "<br><font color='#40BEA9'><b>"
                ).append(line.substring(3))
                 .append("</b></font><br>");

            } else if (line.startsWith("# ")) {

                html.append(
                        "<big><font color='#F1F3F5'><b>"
                ).append(line.substring(2))
                 .append("</b></font></big><br>");

            } else if (line.startsWith("- ")) {

                html.append(
                        "<font color='#40BEA9'>• </font>"
                ).append("<font color='#CDD2D8'>")
                 .append(line.substring(2))
                 .append("</font><br>");

            } else {

                html.append(
                        "<font color='#AEB6BF'>"
                ).append(line)
                 .append("</font><br>");
            }
        }

        return html.toString();
    }

    private TextView createText(
            String text,
            float size,
            int color,
            boolean bold) {

        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);

        if (bold) {
            view.setTypeface(null, Typeface.BOLD);
        }

        return view;
    }

    private void showUpdateDialog(
            String version,
            String changelog,
            String apkUrl) {

        if (isFinishing() || isDestroyed()) {
            return;
        }

        // Main popup container

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);

        layout.setPadding(
                dp(22),
                dp(21),
                dp(22),
                dp(12)
        );

        layout.setBackground(
                roundedBackground(DARK_BG, 20)
        );

        // Small label

        TextView label = createText(
                "NOVEL TRACKER  /  UPDATE",
                10,
                ACCENT,
                true
        );

        label.setLetterSpacing(0.08f);
        layout.addView(label);

        // Main title

        TextView title = createText(
                "Update available",
                23,
                TEXT_PRIMARY,
                true
        );

        LinearLayout.LayoutParams titleParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );

        titleParams.topMargin = dp(7);
        layout.addView(title, titleParams);

        // Version information

        TextView subtitle = createText(
                "Version " + version + " is ready to install.",
                12,
                TEXT_SECONDARY,
                false
        );

        LinearLayout.LayoutParams subtitleParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );

        subtitleParams.topMargin = dp(5);
        subtitleParams.bottomMargin = dp(17);

        layout.addView(subtitle, subtitleParams);

        // Divider

        ViewGroup divider = new ViewGroup(this) {
            @Override
            protected void onMeasure(
                    int widthMeasureSpec,
                    int heightMeasureSpec) {
                setMeasuredDimension(
                        MeasureSpec.getSize(widthMeasureSpec),
                        dp(1)
                );
            }

            @Override
            protected void onLayout(
                    boolean changed,
                    int l,
                    int t,
                    int r,
                    int b) {
            }
        };

        divider.setBackgroundColor(0xFF34383D);

        layout.addView(
                divider,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(1)
                )
        );

        // Scrollable release notes

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(false);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.setOverScrollMode(
                ScrollView.OVER_SCROLL_NEVER
        );

        TextView notes = new TextView(this);
        notes.setTextSize(13);
        notes.setTextColor(TEXT_BODY);
        notes.setLineSpacing(dp(4), 1.0f);
        notes.setPadding(0, dp(14), dp(5), dp(12));

        String formatted = formatChangelog(changelog);

        if (Build.VERSION.SDK_INT >= 24) {
            notes.setText(Html.fromHtml(
                    formatted,
                    Html.FROM_HTML_MODE_LEGACY
            ));
        } else {
            notes.setText(Html.fromHtml(formatted));
        }

        scrollView.addView(notes);

        layout.addView(
                scrollView,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(320)
                )
        );

        // Dialog

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(layout)
                .setNegativeButton("Later", null)
                .setPositiveButton(
                        "Update Now",
                        (d, which) -> {

                            try {
                                Intent intent = new Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse(apkUrl)
                                );

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
                .setCancelable(true)
                .create();

        dialog.setOnShowListener(d -> {

            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(
                        roundedBackground(DARK_BG, 20)
                );

                dialog.getWindow().setDimAmount(0.65f);
            }

            Button laterButton = dialog.getButton(
                    AlertDialog.BUTTON_NEGATIVE
            );

            Button updateButton = dialog.getButton(
                    AlertDialog.BUTTON_POSITIVE
            );

            if (laterButton != null) {
                laterButton.setTextColor(TEXT_SECONDARY);
                laterButton.setTextSize(12);
                laterButton.setTypeface(null, Typeface.BOLD);
                laterButton.setAllCaps(false);
            }

            if (updateButton != null) {
                updateButton.setTextColor(ACCENT);
                updateButton.setTextSize(12);
                updateButton.setTypeface(null, Typeface.BOLD);
                updateButton.setAllCaps(false);
            }
        });

        dialog.show();
    }

    // ==========================================
    // ANDROID BACKUP BRIDGE
    // ==========================================

    private class AndroidBridge {

        @JavascriptInterface
        public void save(String filename, String base64Data) {

            try {
                if (base64Data == null || base64Data.isEmpty()) {
                    return;
                }

                pendingSaveData = Base64.decode(
                        base64Data,
                        Base64.DEFAULT
                );

                pendingFileName = filename;

                runOnUiThread(() -> {

                    try {
                        Intent intent = new Intent(
                                Intent.ACTION_CREATE_DOCUMENT
                        );

                        intent.addCategory(
                                Intent.CATEGORY_OPENABLE
                        );

                        intent.setType("application/zip");

                        intent.putExtra(
                                Intent.EXTRA_TITLE,
                                pendingFileName != null
                                        ? pendingFileName
                                        : "NovelTracker-Backup.zip"
                        );

                        startActivityForResult(
                                intent,
                                REQUEST_SAVE
                        );

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

    // ==========================================
    // FILE IMPORT AND BACKUP EXPORT RESULTS
    // ==========================================

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data) {

        super.onActivityResult(
                requestCode,
                resultCode,
                data
        );

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

    // ==========================================
    // ANDROID BACK BUTTON
    // ==========================================

    @Override
    public void onBackPressed() {

        if (webView != null && webView.canGoBack()) {

            webView.goBack();

        } else {

            super.onBackPressed();
        }
    }
}
