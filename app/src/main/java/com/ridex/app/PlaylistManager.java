package com.ridex.app;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
public class PlaylistManager {
    private static final String PREFS = "ridex_playlists";
    private static final String KEY   = "data";
    public static class Playlist {
        public String name;
        public List<String> uris   = new ArrayList<>();
        public List<String> titles = new ArrayList<>();
        public Playlist(String name) { this.name = name; }
    }
    private final Context ctx;
    private final List<Playlist> list = new ArrayList<>();
    public PlaylistManager(Context ctx) { this.ctx = ctx; load(); }
    public List<Playlist> getAll() { return list; }
    public Playlist get(int i) { return (i >= 0 && i < list.size()) ? list.get(i) : null; }
    public int size() { return list.size(); }
    public void addFromFolder(Uri treeUri) {
        try {
            String seg = treeUri.getLastPathSegment();
            String name = (seg != null && seg.contains(":")) ? seg.substring(seg.lastIndexOf(':') + 1) : seg;
            if (name == null || name.trim().isEmpty()) name = "Playlist " + (list.size() + 1);
            Playlist pl = new Playlist(name.trim());
            String treeId = DocumentsContract.getTreeDocumentId(treeUri);
            Uri childUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeId);
            String[] cols = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            };
            Cursor cursor = ctx.getContentResolver().query(childUri, cols, null, null,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    String docId = cursor.getString(0);
                    String mime  = cursor.getString(1);
                    String title = cursor.getString(2);
                    if (mime != null && mime.startsWith("audio/")) {
                        Uri fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
                        pl.uris.add(fileUri.toString());
                        if (title != null && title.contains("."))
                            title = title.substring(0, title.lastIndexOf('.'));
                        pl.titles.add(title != null ? title : docId);
                    }
                }
                cursor.close();
            }
            if (!pl.uris.isEmpty()) { list.add(pl); save(); }
        } catch (Exception e) { e.printStackTrace(); }
    }
    public String serializeForRemote() {
        try {
            JSONArray root = new JSONArray();
            for (Playlist pl : list) {
                JSONObject o = new JSONObject();
                o.put("name", pl.name);
                JSONArray songs = new JSONArray();
                for (String t : pl.titles) songs.put(t);
                o.put("songs", songs);
                root.put(o);
            }
            return root.toString();
        } catch (Exception e) { return "[]"; }
    }
    private void save() {
        try {
            JSONArray root = new JSONArray();
            for (Playlist pl : list) {
                JSONObject o = new JSONObject();
                o.put("name", pl.name);
                JSONArray u = new JSONArray(); for (String s : pl.uris)   u.put(s);
                JSONArray t = new JSONArray(); for (String s : pl.titles) t.put(s);
                o.put("uris", u); o.put("titles", t);
                root.put(o);
            }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
               .edit().putString(KEY, root.toString()).apply();
        } catch (Exception e) {}
    }
    private void load() {
        try {
            String raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "");
            if (raw.isEmpty()) return;
            JSONArray root = new JSONArray(raw);
            for (int i = 0; i < root.length(); i++) {
                JSONObject o = root.getJSONObject(i);
                Playlist pl = new Playlist(o.getString("name"));
                JSONArray u = o.getJSONArray("uris");
                JSONArray t = o.getJSONArray("titles");
                for (int j = 0; j < u.length(); j++) {
                    pl.uris.add(u.getString(j));
                    pl.titles.add(t.getString(j));
                }
                list.add(pl);
            }
        } catch (Exception e) {}
    }
}
