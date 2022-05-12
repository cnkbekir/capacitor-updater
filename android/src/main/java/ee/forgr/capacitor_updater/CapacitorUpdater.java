package ee.forgr.capacitor_updater;

import android.content.SharedPreferences;
import android.util.Log;

import com.android.volley.AuthFailureError;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.StringRequest;
import com.getcapacitor.plugin.WebView;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

interface Callback {
    void callback(JSONObject jsonObject);
}

public class CapacitorUpdater {
    private static final String AB = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final SecureRandom rnd = new SecureRandom();

    private static final String DOWNLOADED_SUFFIX = "_downloaded";
    private static final String NAME_SUFFIX = "_name";
    private static final String STATUS_SUFFIX = "_status";

    private static final String FALLBACK_VERSION = "pastVersion";
    private static final String NEXT_VERSION = "nextVersion";
    private static final String bundleDirectory = "versions";

    public static final String TAG = "Capacitor-updater";
    public static final String pluginVersion = "4.0.0";

    private SharedPreferences.Editor editor;
    private SharedPreferences prefs;

    private RequestQueue requestQueue;

    private File documentsDir;
    private String versionBuild = "";
    private String versionCode = "";
    private String versionOs = "";

    private String statsUrl = "";
    private String appId = "";
    private String deviceID = "";

    private final FilenameFilter filter = new FilenameFilter() {
        @Override
        public boolean accept(final File f, final String name) {
            // ignore directories generated by mac os x
            return !name.startsWith("__MACOSX") && !name.startsWith(".") && !name.startsWith(".DS_Store");
        }
    };

    private int calcTotalPercent(final int percent, final int min, final int max) {
        return (percent * (max - min)) / 100 + min;
    }

    void notifyDownload(final int percent) {
        return;
    }

    private String randomString(final int len){
        final StringBuilder sb = new StringBuilder(len);
        for(int i = 0; i < len; i++)
            sb.append(AB.charAt(rnd.nextInt(AB.length())));
        return sb.toString();
    }

    private File unzip(final File zipFile, final String dest) throws IOException {
        final File targetDirectory = new File(this.getDocumentsDir(), dest);
        final ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)));
        try {
            int count;
            final int bufferSize = 8192;
            final byte[] buffer = new byte[bufferSize];
            final long lengthTotal = zipFile.length();
            long lengthRead = bufferSize;
            int percent = 0;
            this.notifyDownload(75);

            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                final File file = new File(targetDirectory, entry.getName());
                final String canonicalPath = file.getCanonicalPath();
                final String canonicalDir = (new File(String.valueOf(targetDirectory))).getCanonicalPath();
                final File dir = entry.isDirectory() ? file : file.getParentFile();

                if (!canonicalPath.startsWith(canonicalDir)) {
                    throw new FileNotFoundException("SecurityException, Failed to ensure directory is the start path : " +
                            canonicalDir + " of " + canonicalPath);
                }

                if (!dir.isDirectory() && !dir.mkdirs()) {
                    throw new FileNotFoundException("Failed to ensure directory: " +
                            dir.getAbsolutePath());
                }

                if (entry.isDirectory()) {
                    continue;
                }

                try(final FileOutputStream outputStream = new FileOutputStream(file)) {
                    while ((count = zis.read(buffer)) != -1)
                        outputStream.write(buffer, 0, count);
                }

                final int newPercent = (int)((lengthRead * 100) / lengthTotal);
                if (lengthTotal > 1 && newPercent != percent) {
                    percent = newPercent;
                    this.notifyDownload(this.calcTotalPercent(percent, 75, 90));
                }

                lengthRead += entry.getCompressedSize();
            }
            return targetDirectory;
        } finally {
            try {
                zis.close();
            } catch (final IOException e) {
                Log.e(TAG, "Failed to close zip input stream", e);
            }
        }
    }

    private void flattenAssets(final File sourceFile, final String dest) throws IOException {
        if (!sourceFile.exists()) {
            throw new FileNotFoundException("Source file not found: " + sourceFile.getPath());
        }
        final File destinationFile = new File(this.getDocumentsDir(), dest);
        destinationFile.getParentFile().mkdirs();
        final String[] entries = sourceFile.list(this.filter);
        if (entries == null || entries.length == 0) {
            throw new IOException("Source file was not a directory or was empty: " + sourceFile.getPath());
        }
        if (entries.length == 1 && !entries[0].equals("index.html")) {
            final File child = new File(sourceFile, entries[0]);
            child.renameTo(destinationFile);
        } else {
            sourceFile.renameTo(destinationFile);
        }
        sourceFile.delete();
    }

    private File downloadFile(final String url, final String dest) throws IOException {

        final URL u = new URL(url);
        final URLConnection connection = u.openConnection();
        final InputStream is = u.openStream();
        final DataInputStream dis = new DataInputStream(is);

        final File target = new File(this.getDocumentsDir(), dest);
        target.getParentFile().mkdirs();
        target.createNewFile();
        final FileOutputStream fos = new FileOutputStream(target);

        final long totalLength = connection.getContentLength();
        final int bufferSize = 1024;
        final byte[] buffer = new byte[bufferSize];
        int length;

        int bytesRead = bufferSize;
        int percent = 0;
        this.notifyDownload(10);
        while ((length = dis.read(buffer))>0) {
            fos.write(buffer, 0, length);
            final int newPercent = (int)((bytesRead * 100) / totalLength);
            if (totalLength > 1 && newPercent != percent) {
                percent = newPercent;
                this.notifyDownload(this.calcTotalPercent(percent, 10, 70));
            }
            bytesRead += length;
        }
        return target;
    }

    private void deleteDirectory(final File file) throws IOException {
        if (file.isDirectory()) {
            final File[] entries = file.listFiles();
            if (entries != null) {
                for (final File entry : entries) {
                    this.deleteDirectory(entry);
                }
            }
        }
        if (!file.delete()) {
            throw new IOException("Failed to delete " + file);
        }
    }

    private void setCurrentBundle(final File bundle) {
        this.editor.putString(WebView.CAP_SERVER_PATH, bundle.getPath());
        Log.i(TAG, "Current bundle set to: " + bundle);
        this.editor.commit();
    }

    public VersionInfo download(final String url, final String versionName) throws IOException {
        this.notifyDownload(0);
        final String path = this.randomString(10);
        final File zipFile = new File(this.getDocumentsDir(), path);
        final String folderNameUnZip = this.randomString(10);
        final String version = this.randomString(10);
        final String folderName = bundleDirectory + "/" + version;
        this.notifyDownload(5);
        final File downloaded = this.downloadFile(url, path);
        this.notifyDownload(71);
        final File unzipped = this.unzip(downloaded, folderNameUnZip);
        zipFile.delete();
        this.notifyDownload(91);
        this.flattenAssets(unzipped, folderName);
        this.notifyDownload(100);
        this.setVersionStatus(version, VersionStatus.PENDING);
        this.setVersionDownloadedTimestamp(version, new Date(System.currentTimeMillis()));
        this.setVersionName(version, versionName);
        return this.getVersionInfo(version);
    }

    public List<VersionInfo> list() {
        final List<VersionInfo> res = new ArrayList<>();
        final File destHot = new File(this.getDocumentsDir(), bundleDirectory);
        Log.i(TAG, "list File : " + destHot.getPath());
        if (destHot.exists()) {
            for (final File i : destHot.listFiles()) {
                final String version = i.getName();
                res.add(this.getVersionInfo(version));
            }
        } else {
            Log.i(TAG, "No version available" + destHot);
        }
        return res;
    }

    public Boolean delete(final String version) throws IOException {
        final VersionInfo deleted = this.getVersionInfo(version);
        final File bundle = new File(this.getDocumentsDir(), bundleDirectory + "/" + version);
        if (bundle.exists()) {
            this.deleteDirectory(bundle);
            this.removeVersionInfo(version);
            return true;
        }
        Log.i(TAG, "Directory not removed: " + bundle.getPath());
        this.sendStats("delete", deleted);
        return false;
    }

    private File getBundleDirectory(final String version) {
        return new File(this.getDocumentsDir(), bundleDirectory + "/" + version);
    }

    private boolean bundleExists(final File bundle) {
        if(bundle == null || !bundle.exists()) {
            return false;
        }

        return new File(bundle.getPath(), "/index.html").exists();
    }

    public Boolean set(final VersionInfo version) {
        return this.set(version.getVersion());
    }

    public Boolean set(final String version) {

        final VersionInfo existing = this.getVersionInfo(version);
        final File bundle = this.getBundleDirectory(version);

        Log.i(TAG, "Setting next active bundle " + existing);
        if (this.bundleExists(bundle)) {
            this.setCurrentBundle(bundle);
            this.setVersionStatus(version, VersionStatus.PENDING);
            this.sendStats("set", existing);
            return true;
        }
        this.sendStats("set_fail", existing);
        return false;
    }

    public void commit(final VersionInfo version) {
        this.setVersionStatus(version.getVersion(), VersionStatus.SUCCESS);
        this.setFallbackVersion(version);
    }

    public void reset() {
        this.reset(false);
    }

    public void rollback(final VersionInfo version) {
        this.setVersionStatus(version.getVersion(), VersionStatus.ERROR);
    }

    public void reset(final boolean internal) {
        this.setCurrentBundle(new File("public"));
        this.setFallbackVersion(null);
        this.setNextVersion(null);
        if(!internal) {
            this.sendStats("reset", this.getCurrentBundle());
        }
    }

    public void getLatest(final String url, final Callback callback) {
        final String deviceID = this.getDeviceID();
        final String appId = this.getAppId();
        final String versionBuild = this.versionBuild;
        final String versionCode = this.versionCode;
        final String versionOs = this.versionOs;
        final String pluginVersion = CapacitorUpdater.pluginVersion;
        final String versionName = this.getCurrentBundle().getName();
        final StringRequest stringRequest = new StringRequest(Request.Method.GET, url,
                new Response.Listener<String>() {
                    @Override
                    public void onResponse(final String response) {
                        try {
                            final JSONObject jsonObject = new JSONObject(response);
                            callback.callback(jsonObject);
                        } catch (final JSONException e) {
                            Log.e(TAG, "Error parsing JSON", e);
                        }
                    }
                }, new Response.ErrorListener() {
            @Override
            public void onErrorResponse(final VolleyError error) {
                Log.e(TAG, "Error getting Latest" +  error);
            }
        }) {
            @Override
            public Map<String, String> getHeaders() throws AuthFailureError {
                final Map<String, String>  params = new HashMap<String, String>();
                params.put("cap_platform", "android");
                params.put("cap_device_id", deviceID);
                params.put("cap_app_id", appId);
                params.put("cap_version_build", versionBuild);
                params.put("cap_version_code", versionCode);
                params.put("cap_version_os", versionOs);
                params.put("cap_version_name", versionName);
                params.put("cap_plugin_version", pluginVersion);
                return params;
            }
        };
        this.requestQueue.add(stringRequest);
    }

    public void sendStats(final String action, final VersionInfo version) {
        if (this.getStatsUrl() == "") { return; }
        final URL url;
        final JSONObject json = new JSONObject();
        final String jsonString;
        try {
            url = new URL(this.getStatsUrl());
            json.put("platform", "android");
            json.put("action", action);
            json.put("version_name", version);
            json.put("device_id", this.getDeviceID());
            json.put("version_build", this.versionBuild);
            json.put("version_code", this.versionCode);
            json.put("version_os", this.versionOs);
            json.put("plugin_version", pluginVersion);
            json.put("app_id", this.getAppId());
            jsonString = json.toString();
        } catch (final Exception ex) {
            Log.e(TAG, "Error get stats", ex);
            return;
        }
        new Thread(new Runnable(){
            @Override
            public void run() {
                HttpURLConnection con = null;
                try {
                    con = (HttpURLConnection) url.openConnection();
                    con.setRequestMethod("POST");
                    con.setRequestProperty("Content-Type", "application/json");
                    con.setRequestProperty("Accept", "application/json");
                    con.setRequestProperty("Content-Length", Integer.toString(jsonString.getBytes().length));
                    con.setDoOutput(true);
                    con.setConnectTimeout(500);
                    final DataOutputStream wr = new DataOutputStream (con.getOutputStream());
                    wr.writeBytes(jsonString);
                    wr.close();
                    final int responseCode = con.getResponseCode();
                    if (responseCode != 200) {
                        Log.e(TAG, "Stats error responseCode: " + responseCode);
                    } else {
                        Log.i(TAG, "Stats send for \"" + action + "\", version " + version);
                    }
                } catch (final Exception ex) {
                    Log.e(TAG, "Error post stats", ex);
                } finally {
                    if (con != null) {
                        con.disconnect();
                    }
                }
            }
        }).start();
    }

    public VersionInfo getVersionInfo(String version) {
        if(version == null) {
            version = "unknown";
        }
        final String downloaded = this.getVersionDownloadedTimestamp(version);
        final String name = this.getVersionName(version);
        final VersionStatus status = this.getVersionStatus(version);
        return new VersionInfo(version, status, downloaded, name);
    }

    public VersionInfo getVersionInfoByName(final String version) {
        final List<VersionInfo> installed = this.list();
        for(final VersionInfo i : installed) {
            if(i.getName().equals(version)) {
                return i;
            }
        }
        return null;
    }

    private void removeVersionInfo(final String version) {
        this.setVersionDownloadedTimestamp(version, null);
        this.setVersionName(version, null);
        this.setVersionStatus(version, null);
    }

    private String getVersionDownloadedTimestamp(final String version) {
        return this.prefs.getString(version + DOWNLOADED_SUFFIX, "");
    }

    private void setVersionDownloadedTimestamp(final String version, final Date time) {
        if(version != null) {
            Log.i(TAG, "Setting version download timestamp " + version + " to " + time);
            if(time == null) {
                this.editor.remove(version + DOWNLOADED_SUFFIX);
            } else {

                final SimpleDateFormat sdf;
                sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
                sdf.setTimeZone(TimeZone.getTimeZone("CET"));
                final String isoDate = sdf.format(time);

                this.editor.putString(version + DOWNLOADED_SUFFIX, isoDate);
            }
            this.editor.commit();
        }
    }

    private String getVersionName(final String version) {
        return this.prefs.getString(version + NAME_SUFFIX, "");
    }

    public void setVersionName(final String version, final String name) {
        if(version != null) {
            Log.i(TAG, "Setting version name " + version + " to " + name);
            if(name == null) {
                this.editor.remove(version + NAME_SUFFIX);
            } else {
                this.editor.putString(version + NAME_SUFFIX, name);
            }
            this.editor.commit();
        }
    }

    private VersionStatus getVersionStatus(final String version) {
        return VersionStatus.fromString(this.prefs.getString(version + STATUS_SUFFIX, "pending"));
    }

    private void setVersionStatus(final String version, final VersionStatus status) {
        if(version != null) {
            Log.i(TAG, "Setting version status " + version + " to " + status);
            if(status == null) {
                this.editor.remove(version + STATUS_SUFFIX);
            } else {
                this.editor.putString(version + STATUS_SUFFIX, status.label);
            }
            this.editor.commit();
        }
    }

    private String getCurrentBundleVersion() {
        if(this.isUsingBuiltin()) {
            return VersionInfo.VERSION_BUILTIN;
        } else {
            final String path = this.getCurrentBundlePath();
            return path.substring(path.lastIndexOf('/') + 1);
        }
    }

    public VersionInfo getCurrentBundle() {
        return this.getVersionInfo(this.getCurrentBundleVersion());
    }

    public String getCurrentBundlePath() {
        return this.prefs.getString(WebView.CAP_SERVER_PATH, "public");
    }

    public Boolean isUsingBuiltin() {
        return this.getCurrentBundlePath().equals("public");
    }

    public VersionInfo getFallbackVersion() {
        final String version = this.prefs.getString(FALLBACK_VERSION, VersionInfo.VERSION_BUILTIN);
        return this.getVersionInfo(version);
    }

    private void setFallbackVersion(final VersionInfo fallback) {
        this.editor.putString(FALLBACK_VERSION,
                fallback == null
                        ? VersionInfo.VERSION_BUILTIN
                        : fallback.getVersion()
        );
    }

    public VersionInfo getNextVersion() {
        final String version = this.prefs.getString(NEXT_VERSION, "");
        if(version != "") {
            return this.getVersionInfo(version);
        } else {
            return null;
        }
    }

    public boolean setNextVersion(final String next) {
        if (next == null) {
            this.editor.remove(NEXT_VERSION);
        } else {
            final File bundle = this.getBundleDirectory(next);
            if (!this.bundleExists(bundle)) {
                return false;
            }

            this.editor.putString(NEXT_VERSION, next);
            this.setVersionStatus(next, VersionStatus.PENDING);
        }
        this.editor.commit();
        return true;
    }

    public String getStatsUrl() {
        return this.statsUrl;
    }

    public void setStatsUrl(final String statsUrl) {
        this.statsUrl = statsUrl;
    }

    public String getAppId() {
        return this.appId;
    }

    public void setAppId(final String appId) {
        this.appId = appId;
    }

    public String getDeviceID() {
        return this.deviceID;
    }

    public void setDeviceID(final String deviceID) {
        this.deviceID = deviceID;
    }

    public void setVersionBuild(final String versionBuild) {
        this.versionBuild = versionBuild;
    }

    public void setVersionCode(final String versionCode) {
        this.versionCode = versionCode;
    }

    public void setVersionOs(final String versionOs) {
        this.versionOs = versionOs;
    }

    public void setPrefs(final SharedPreferences prefs) {
        this.prefs = prefs;
    }

    public void setEditor(final SharedPreferences.Editor editor) {
        this.editor = editor;
    }

    public void setDocumentsDir(final File documentsDir) {
        this.documentsDir = documentsDir;
    }

    public void setRequestQueue(final RequestQueue requestQueue) {
        this.requestQueue = requestQueue;
    }

    public File getDocumentsDir() {
        return this.documentsDir;
    }
}
