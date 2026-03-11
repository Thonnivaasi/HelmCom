package com.ridex.app;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import org.json.*;
import java.util.*;

public class PlaylistManager {
    public static class Playlist {
        public String name;
        public List<String> uris   = new ArrayList<>();
        public List<String> titles = new ArrayList<>();
    }

    private final Context ctx;
    private final List<Playlist> list = new ArrayList<>();

    public PlaylistManager(Context ctx) { this.ctx = ctx; }

    public void addFromFolder(Uri treeUri) {
        Playlist pl = new Playlist();
        // Use last path segment as playlist name
        String path = treeUri.getLastPathSegment();
        pl.name = path != null && path.contains(":") ? path.substring(path.lastIndexOf(':') + 1) : path;
        if (pl.name == null || pl.name.isEmpty()) pl.name = "Playlist " + (list.size() + 1);

        try {
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri));
            Cursor c = ctx.getContentResolver().query(childrenUri,
                new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                }, null, null, null);
            if (c != null) {
                while (c.moveToNext()) {
                    String mime  = c.getString(2);
                    String docId = c.getString(0);
                    String name  = c.getString(1);
                    if (mime != null && (mime.startsWith("audio/") ||
                        name.toLowerCase().endsWith(".mp3") ||
                        name.toLowerCase().endsWith(".flac") ||
                        name.toLowerCase().endsWith(".m4a") ||
                        name.toLowerCase().endsWith(".ogg") ||
                        name.toLowerCase().endsWith(".wav"))) {
                        Uri fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
                        pl.uris.add(fileUri.toString());
                        // Clean up display name
                        String title = name.replaceAll("\\.[^.]+$", ""); // remove extension
                        pl.titles.add(title);
                    }
                }
                c.close();
            }
        } catch (Exception e) { e.printStackTrace(); }

        if (!pl.uris.isEmpty()) {
            list.add(pl);
        }
    }

    public List<Playlist> getAll() { return list; }
    public Playlist get(int i)     { return (i >= 0 && i < list.size()) ? list.get(i) : null; }
    public int size()              { return list.size(); }

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
}
