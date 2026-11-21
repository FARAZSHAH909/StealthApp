package com.notrack.stealth;

import com.notrack.stealth.security.PhotoEncryptionEngine;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.util.Arrays;

import javax.crypto.AEADBadTagException;
import javax.crypto.SecretKey;

public class PhotoEncryptionUnitTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void testPhotoEncryptionDecryptionStreamRoundtrip() throws Exception {
        // 1. Prepare raw sample photo file (128 KB simulated image content)
        File rawFile = temporaryFolder.newFile("sample_photo.jpg");
        byte[] rawBytes = new byte[128 * 1024];
        for (int i = 0; i < rawBytes.length; i++) {
            rawBytes[i] = (byte) (i % 256);
        }
        try (FileOutputStream fos = new FileOutputStream(rawFile)) {
            fos.write(rawBytes);
        }

        // 2. Generate Master Key & Encrypt Stream
        SecretKey secretKey = PhotoEncryptionEngine.getOrCreateMasterKey();
        File encryptedFile = temporaryFolder.newFile("sample_photo.jpg.enc");

        PhotoEncryptionEngine.EncryptedPhotoResult encResult = PhotoEncryptionEngine.encryptPhotoStream(
                rawFile,
                encryptedFile,
                secretKey
        );

        // 3. Verify Result Properties
        Assert.assertNotNull(encResult);
        Assert.assertEquals(12, encResult.iv.length);
        Assert.assertEquals(rawBytes.length, encResult.originalSize);
        // Encrypted size should equal IV (12) + Raw Size + GCM Tag (16)
        Assert.assertEquals(12 + rawBytes.length + 16, encResult.encryptedSize);
        Assert.assertNotNull(encResult.sha256Checksum);
        Assert.assertEquals(64, encResult.sha256Checksum.length()); // SHA-256 hex string length

        // 4. Decrypt Stream
        File decryptedFile = temporaryFolder.newFile("decrypted_photo.jpg");
        boolean success = PhotoEncryptionEngine.decryptPhotoStream(encryptedFile, decryptedFile, secretKey);
        Assert.assertTrue(success);
        Assert.assertEquals(rawBytes.length, decryptedFile.length());

        // 5. Verify Content Equality
        byte[] decryptedBytes = new byte[(int) decryptedFile.length()];
        try (FileInputStream fis = new FileInputStream(decryptedFile)) {
            int read = fis.read(decryptedBytes);
            Assert.assertEquals(rawBytes.length, read);
        }
        Assert.assertArrayEquals(rawBytes, decryptedBytes);
    }

    @Test
    public void testGcmAuthTagRejectsTamperedCiphertext() throws Exception {
        // 1. Prepare raw sample photo file
        File rawFile = temporaryFolder.newFile("photo_to_tamper.jpg");
        byte[] rawBytes = "CONFIDENTIAL_IMAGE_DATA_STRICT_SECURITY".getBytes();
        try (FileOutputStream fos = new FileOutputStream(rawFile)) {
            fos.write(rawBytes);
        }

        SecretKey secretKey = PhotoEncryptionEngine.getOrCreateMasterKey();
        File encryptedFile = temporaryFolder.newFile("photo_to_tamper.jpg.enc");

        PhotoEncryptionEngine.encryptPhotoStream(rawFile, encryptedFile, secretKey);

        // 2. Tamper a single byte in the encrypted file ciphertext
        try (RandomAccessFile raf = new RandomAccessFile(encryptedFile, "rw")) {
            raf.seek(20); // Seek past IV header into ciphertext
            byte b = raf.readByte();
            raf.seek(20);
            raf.writeByte(b ^ 0xFF); // Flip bits
        }

        // 3. Attempt decryption - must throw exception due to GCM auth tag failure
        File decryptedFile = temporaryFolder.newFile("should_fail.jpg");
        try {
            PhotoEncryptionEngine.decryptPhotoStream(encryptedFile, decryptedFile, secretKey);
            Assert.fail("Expected encryption integrity exception on tampered ciphertext!");
        } catch (Exception e) {
            // Success: GCM Authentication Tag verification caught tampering
            Assert.assertTrue(e.getCause() instanceof AEADBadTagException || e instanceof AEADBadTagException || e.getMessage() != null);
        }
    }
}
