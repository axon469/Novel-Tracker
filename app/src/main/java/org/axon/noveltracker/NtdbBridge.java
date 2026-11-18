package org.axon.noveltracker;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import androidx.documentfile.provider.DocumentFile;

import java.io.OutputStream;

/**
 * JavaScript bridge exposed to the web app as "NTDB".
 *
 * Folder picking uses Intent.ACTION_OPEN_DOCUMENT_TREE and persists the permission, so the folder
 * is remembered after the app restarts. Two folders are kept separately:
 *  - the manual Export folder   (pickFolder / beginSave / save)
 *  - the Automatic Backup folder (pickAutoFolder / beginAutoSave)
 * Files are written in chunks (beginSave -> chunk... -> endSave), so very large backups never have
 * to cross the bridge as a single giant string.
 */
public class NtdbBridge {
    private static final int REQ_EXPORT = 7101;
    private static final int REQ_AUTO = 7102;
    private static final String PREFS = "ntdb_bridge";
    private static final String K_EXPORT = "export_folder";
    private static final String K_AUTO = "auto_folder";
    private static final int RW = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;

    private final Activity act;
    private final WebView web;
    private final SharedPreferences prefs;
    private OutputStream out;
    private DocumentFile curFile;

    public NtdbBridge(Activity activity, WebView webView) {
        this.act = activity;
        this.web = webView;
        this.prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- folder picking

    @JavascriptInterface
    public void pickFolder() { launch(REQ_EXPORT, K_EXPORT); }

    @JavascriptInterface
    public void pickAutoFolder() { launch(REQ_AUTO, K_AUTO); }

    private void launch(final int req, final String key) {
        act.runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                    i.addFlags(RW | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
                    String saved = prefs.getString(key, null);
                    if (saved != null && Build.VERSION.SDK_INT >= 26) {
                        i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(saved));
                    }
                    act.startActivityForResult(i, req);
                } catch (Exception e) {
                    callback(false);
                }
            }
        });
    }

    /** Call this from MainActivity.onActivityResult. Returns true when the result was handled here. */
    public boolean onActivityResult(int req, int res, Intent data) {
        if (req != REQ_EXPORT && req != REQ_AUTO) return false;
        if (res != Activity.RESULT_OK || data == null || data.getData() == null) {
            callback(false); // cancelled
            return true;
        }
        Uri uri = data.getData();
        String key = (req == REQ_AUTO) ? K_AUTO : K_EXPORT;
        try {
            act.getContentResolver().takePersistableUriPermission(uri, RW);
            String old = prefs.getString(key, null);
            if (old != null && !old.equals(uri.toString())) releaseIfUnused(Uri.parse(old), key);
            prefs.edit().putString(key, uri.toString()).apply();
            DocumentFile dir = DocumentFile.fromTreeUri(act, uri);
            callback(dir != null && dir.canWrite());
        } catch (Exception e) { // SecurityException or provider errors
            callback(false);
        }
        return true;
    }

    private void releaseIfUnused(Uri old, String key) {
        String other = prefs.getString(K_AUTO.equals(key) ? K_EXPORT : K_AUTO, null);
        if (other != null && other.equals(old.toString())) return;
        try { act.getContentResolver().releasePersistableUriPermission(old, RW); } catch (Exception ignored) { }
    }

    /** Display name of the saved folder, or "" if it is missing or the permission was lost. */
    @JavascriptInterface
    public String folderName() { return nameOf(K_EXPORT); }

    @JavascriptInterface
    public String autoFolderName() { return nameOf(K_AUTO); }

    private String nameOf(String key) {
        try {
            String s = prefs.getString(key, null);
            if (s == null) return "";
            Uri u = Uri.parse(s);
            if (!hasPermission(u)) return "";
            DocumentFile d = DocumentFile.fromTreeUri(act, u);
            if (d == null || !d.exists() || !d.canWrite()) return "";
            String n = d.getName();
            return n == null ? "Selected folder" : n;
        } catch (Exception e) {
            return "";
        }
    }

    private boolean hasPermission(Uri u) {
        for (UriPermission p : act.getContentResolver().getPersistedUriPermissions()) {
            if (p.getUri().equals(u) && p.isWritePermission()) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- saving

    /** Legacy one-shot save (kept for compatibility). Returns true on success. */
    @JavascriptInterface
    public boolean save(String name, String base64) {
        if (!begin(name, K_EXPORT)) return false;
        boolean ok = chunk(base64);
        return endSave() && ok;
    }

    @JavascriptInterface
    public boolean beginSave(String name) { return begin(name, K_EXPORT); }

    @JavascriptInterface
    public boolean beginAutoSave(String name) { return begin(name, K_AUTO); }

    private synchronized boolean begin(String name, String key) {
        abort();
        try {
            String s = prefs.getString(key, null);
            if (s == null) return false;
            Uri u = Uri.parse(s);
            if (!hasPermission(u)) return false;
            DocumentFile dir = DocumentFile.fromTreeUri(act, u);
            if (dir == null || !dir.canWrite()) return false;
            String base = safe(name), unique = base;
            int n = 1;
            while (dir.findFile(unique) != null) {
                int dot = base.lastIndexOf('.');
                unique = dot > 0 ? base.substring(0, dot) + " (" + (n++) + ")" + base.substring(dot) : base + " (" + (n++) + ")";
            }
            DocumentFile f = dir.createFile("application/zip", unique);
            if (f == null) return false;
            out = act.getContentResolver().openOutputStream(f.getUri(), "w");
            curFile = f;
            return out != null;
        } catch (Exception e) {
            abort();
            return false;
        }
    }

    @JavascriptInterface
    public synchronized boolean chunk(String base64) {
        if (out == null) return false;
        try {
            out.write(Base64.decode(base64, Base64.DEFAULT));
            return true;
        } catch (Exception e) {
            abort();
            return false;
        }
    }

    @JavascriptInterface
    public synchronized boolean endSave() {
        if (out == null) return false;
        try {
            out.flush();
            out.close();
            out = null;
            curFile = null;
            return true;
        } catch (Exception e) {
            abort();
            return false;
        }
    }

    private void abort() {
        try { if (out != null) out.close(); } catch (Exception ignored) { }
        out = null;
        if (curFile != null) {
            try { curFile.delete(); } catch (Exception ignored) { }
            curFile = null;
        }
    }

    private static String safe(String n) {
        String s = n == null ? "backup.zip" : n.replaceAll("[\\\\/:*?\"<>|]+", "_");
        return s.isEmpty() ? "backup.zip" : s;
    }

    private void callback(final boolean ok) {
        web.post(new Runnable() {
            @Override public void run() {
                web.evaluateJavascript("window.ntdbFolder&&window.ntdbFolder(" + ok + ")", null);
            }
        });
    }
}
