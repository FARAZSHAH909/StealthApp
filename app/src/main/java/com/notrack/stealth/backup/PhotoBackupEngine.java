package com.notrack.stealth.backup;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Log;

import com.notrack.stealth.security.PhotoEncryptionEngine;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.SecretKey;

/**
 * PRODUCTION-GRADE AUTOMATED PHOTO BACKUP ENGINE
 *
 * Scans local media, streams photos through client-side AES-256-GCM encryption,
 * uploads to Google Drive appDataFolder, and verifies size & SHA-256 integrity.
 */
public class PhotoBackupEngine {

    private static final String TAG = "PhotoBackupEngine";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    public static class LocalMediaItem {
        public final Uri contentUri;
        public final String displayName;
        public final long size;

        public LocalMediaItem(Uri contentUri, String displayName, long size) {
            this.contentUri = contentUri;
            this.displayName = displayName;
            this.size = size;
        }
    }

    public static void performPhotoBackupAsync(Context context, String oauthAccessToken) {
        if (context == null || oauthAccessToken == null) return;
        final Context appCtx = context.getApplicationContext();
        EXECUTOR.execute(() -> performPhotoBackupSync(appCtx, oauthAccessToken));
    }

    public static int performPhotoBackupSync(Context context, String oauthAccessToken) {
        if (context == null || oauthAccessToken == null) return 0;

        Log.i(TAG, "Starting automated encrypted photo backup sequence...");
        int successCount = 0;

        try {
            SecretKey masterKey = PhotoEncryptionEngine.getOrCreateMasterKey();
            List<LocalMediaItem> mediaItems = scanLocalPhotos(context, 20);

            for (LocalMediaItem item : mediaItems) {
                File tempRawFile = null;
                File tempEncryptedFile = null;
                try {
                    // Step 1: Copy MediaStore Uri content to temp file
                    tempRawFile = File.createTempFile("raw_photo_", ".tmp", context.getCacheDir());
                    copyUriToFile(context, item.contentUri, tempRawFile);

                    // Step 2: Encrypt photo via AES-256-GCM
                    tempEncryptedFile = File.createTempFile("enc_photo_", ".tmp.enc", context.getCacheDir());
                    PhotoEncryptionEngine.EncryptedPhotoResult encResult = PhotoEncryptionEngine.encryptPhotoStream(
                            tempRawFile,
                            tempEncryptedFile,
                            masterKey
                    );

                    // Step 3: Upload & Verify on Google Drive
                    GoogleDriveBackupEngine.UploadVerificationResult verifyResult = GoogleDriveBackupEngine.uploadAndVerifyPhoto(
                            oauthAccessToken,
                            encResult,
                            item.displayName
                    );

                    if (verifyResult.success) {
                        successCount++;
                        Log.i(TAG, "Successfully backed up & verified photo: " + item.displayName + " -> Drive ID: " + verifyResult.fileId);
                    } else {
                        Log.e(TAG, "Failed photo backup for " + item.displayName + ": " + verifyResult.errorMessage);
                    }

                } catch (Exception e) {
                    Log.e(TAG, "Error processing photo backup for " + item.displayName + ": " + e.getMessage(), e);
                } finally {
                    if (tempRawFile != null && tempRawFile.exists()) tempRawFile.delete();
                    if (tempEncryptedFile != null && tempEncryptedFile.exists()) tempEncryptedFile.delete();
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Fatal error during photo backup workflow: " + e.getMessage(), e);
        }

        Log.i(TAG, "Completed photo backup sequence. Uploaded " + successCount + " files.");
        return successCount;
    }

    public static List<LocalMediaItem> scanLocalPhotos(Context context, int limit) {
        List<LocalMediaItem> items = new ArrayList<>();
        if (context == null) return items;

        Uri uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] projection = new String[]{
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.SIZE
        };

        Cursor cursor = null;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.os.Bundle queryArgs = new android.os.Bundle();
                queryArgs.putStringArray(android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS,
                        new String[]{MediaStore.Images.Media.DATE_ADDED});
                queryArgs.putInt(android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION,
                        android.content.ContentResolver.QUERY_SORT_DIRECTION_DESCENDING);
                queryArgs.putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, limit);
                cursor = context.getContentResolver().query(uri, projection, queryArgs, null);
            } else {
                String sortOrder = MediaStore.Images.Media.DATE_ADDED + " DESC";
                cursor = context.getContentResolver().query(uri, projection, null, null, sortOrder);
            }

            if (cursor != null && cursor.moveToFirst()) {
                int idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
                int nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME);
                int sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE);

                int count = 0;
                do {
                    long id = cursor.getLong(idCol);
                    String name = cursor.getString(nameCol);
                    long size = cursor.getLong(sizeCol);

                    if (name == null) name = "photo_" + id + ".jpg";
                    Uri contentUri = Uri.withAppendedPath(uri, String.valueOf(id));
                    items.add(new LocalMediaItem(contentUri, name, size));
                    count++;
                    if (count >= limit) break;
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error querying MediaStore images: " + e.getMessage());
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Exception ignored) {}
            }
        }

        return items;
    }

    private static void copyUriToFile(Context context, Uri uri, File destFile) throws Exception {
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(destFile)) {
            if (in == null) throw new IllegalStateException("Unable to open stream for URI: " + uri);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
        }
    }
}
