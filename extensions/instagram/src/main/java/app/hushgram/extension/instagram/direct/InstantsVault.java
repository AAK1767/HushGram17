/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

import app.hushgram.extension.instagram.download.InstagramMedia;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;

/** Stores received Instant media captured while the Instagram item is constructed. */
public final class InstantsVault {
    private InstantsVault() {}

    private static volatile String lastCapturedId;

    /** Called by the Save Instants patch with the item's Media object. */
    public static void noteMedia(Object media) {
        if (media == null) return;
        try {
            if (!Utils.settingsReady() || !Settings.SAVE_INSTANTS.get()) return;

            String id = InstagramMedia.mediaId(media);
            if (id == null || id.isEmpty() || id.equals(lastCapturedId)) return;

            Context context = Utils.getContext();
            if (context == null) return;

            Database db = Database.get(context);
            if (db.has(id)) return;

            Object owner = InstagramMedia.owner(media);
            String username = owner == null ? null : InstagramMedia.username(owner);

            // Allowlist: if set, only capture from listed users
            String allowlistRaw = Settings.SAVE_INSTANTS_ALLOWLIST.get();
            if (allowlistRaw != null && !allowlistRaw.trim().isEmpty()) {
                String[] allowed = allowlistRaw.split(",");
                boolean matched = false;
                String lowerUser = username == null ? "" : username.trim().toLowerCase(java.util.Locale.ROOT);
                for (String entry : allowed) {
                    String e = entry.trim().toLowerCase(java.util.Locale.ROOT);
                    if (!e.isEmpty() && e.equals(lowerUser)) { matched = true; break; }
                }
                if (!matched) return;
            }

            Long takenAt = InstagramMedia.takenAt(media);

            List<?> videoVersions = InstagramMedia.videoVersions(media);
            String videoUrl = null;
            if (videoVersions != null) {
                for (Object version : videoVersions) {
                    String url = InstagramMedia.versionUrl(version);
                    if (url != null && !url.isEmpty()) {
                        videoUrl = url;
                        break;
                    }
                }
            }

            String imageUrl = null;
            Object imageVersions = InstagramMedia.imageVersions(media);
            if (imageVersions != null) {
                List<?> candidates = InstagramMedia.imageCandidates(imageVersions);
                if (candidates != null) {
                    Object largest = null;
                    int largestSize = -1;
                    for (Object candidate : candidates) {
                        int size = InstagramMedia.candidateWidth(candidate) *
                                InstagramMedia.candidateHeight(candidate);
                        if (size > largestSize) {
                            largestSize = size;
                            largest = candidate;
                        }
                    }
                    if (largest != null) imageUrl = InstagramMedia.candidateUrl(largest);
                }
            }

            if (videoUrl == null && imageUrl == null) return;
            db.insert(id, username, imageUrl, videoUrl, videoUrl != null,
                    takenAt == null ? null : takenAt * 1000L);
            lastCapturedId = id;
        } catch (Throwable failure) {
            Logger.printException(() -> "Save Instants: could not capture an Instant", failure);
        }
    }

    public static final class Database extends SQLiteOpenHelper {
        private static volatile Database instance;

        public static synchronized Database get(Context context) {
            if (instance == null) instance = new Database(context.getApplicationContext());
            return instance;
        }

        private Database(Context context) {
            super(context, "hushgram_instants.db", null, 1);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE instants (" +
                    "media_id TEXT PRIMARY KEY," +
                    "username TEXT," +
                    "image_url TEXT," +
                    "video_url TEXT," +
                    "is_video INTEGER," +
                    "taken_at_ms INTEGER," +
                    "captured_at_ms INTEGER)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}

        public boolean has(String id) {
            try (Cursor cursor = getReadableDatabase().query(
                    "instants", new String[]{"media_id"}, "media_id=?",
                    new String[]{id}, null, null, null, "1")) {
                return cursor.moveToFirst();
            } catch (Throwable ignored) {
                return false;
            }
        }

        public void insert(String id, String username, String imageUrl, String videoUrl,
                    boolean video, Long takenAtMillis) {
            try {
                ContentValues values = new ContentValues();
                values.put("media_id", id);
                values.put("username", username);
                values.put("image_url", imageUrl);
                values.put("video_url", videoUrl);
                values.put("is_video", video ? 1 : 0);
                values.put("taken_at_ms", takenAtMillis);
                values.put("captured_at_ms", System.currentTimeMillis());
                getWritableDatabase().insertWithOnConflict(
                        "instants", null, values, SQLiteDatabase.CONFLICT_IGNORE);
            } catch (Throwable failure) {
                Logger.printException(() -> "Save Instants: could not write the vault", failure);
            }
        }

        public List<String[]> allRows() {
            List<String[]> rows = new ArrayList<>();
            try (Cursor cursor = getReadableDatabase().query(
                    "instants",
                    new String[]{"media_id", "username", "image_url", "video_url",
                            "is_video", "taken_at_ms"},
                    null, null, null, null, "captured_at_ms DESC")) {
                while (cursor.moveToNext()) {
                    rows.add(new String[]{
                            cursor.getString(0),
                            cursor.getString(1),
                            cursor.getString(2),
                            cursor.getString(3),
                            String.valueOf(cursor.getInt(4)),
                            cursor.isNull(5) ? null : String.valueOf(cursor.getLong(5)),
                    });
                }
            } catch (Throwable failure) {
                Logger.printException(() -> "Save Instants: could not read the vault", failure);
            }
            return rows;
        }

        /** Returns [image_url, video_url, is_video("0"/"1")] for the given id, or null. */
        public String[] getUrls(String id) {
            try (Cursor cursor = getReadableDatabase().query(
                    "instants", new String[]{"image_url", "video_url", "is_video"},
                    "media_id=?", new String[]{id}, null, null, null, "1")) {
                if (!cursor.moveToFirst()) return null;
                return new String[]{cursor.getString(0), cursor.getString(1),
                        String.valueOf(cursor.getInt(2))};
            } catch (Throwable ignored) {
                return null;
            }
        }

        /** Removes a single row by id. */
        public void delete(String id) {
            try {
                getWritableDatabase().delete("instants", "media_id=?", new String[]{id});
            } catch (Throwable failure) {
                Logger.printException(() -> "Save Instants: could not delete from vault", failure);
            }
        }

        /** Removes all rows for a given username. */
        public void deleteByUsername(String username) {
            try {
                getWritableDatabase().delete("instants", "username=?", new String[]{username});
            } catch (Throwable failure) {
                Logger.printException(() -> "Save Instants: could not delete group from vault", failure);
            }
        }

        /** Removes all rows. */
        public void clearAll() {
            try {
                getWritableDatabase().delete("instants", null, null);
            } catch (Throwable failure) {
                Logger.printException(() -> "Save Instants: clearAll failed", failure);
            }
        }
    }
}
