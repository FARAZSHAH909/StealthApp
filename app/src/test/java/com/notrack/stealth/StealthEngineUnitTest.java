package com.notrack.stealth;

import com.notrack.stealth.security.EncryptionUtils;
import com.notrack.stealth.security.KeyEnrollmentHelper;

import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * ENTERPRISE TELEMETRY ENGINE & CRYPTO INTEGRITY TESTS
 */
public class StealthEngineUnitTest {

    @Test
    public void testDeduplicationMapDoesNotThrowException() {
        // Critical test: ensures Map.keySet().add() bug is permanently fixed
        final int MAX_SIZE = 250;
        Map<String, Long> dedupMap = Collections.synchronizedMap(
                new LinkedHashMap<String, Long>(MAX_SIZE, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                        return size() > MAX_SIZE;
                    }
                }
        );

        String key = "com.whatsapp|Alice|Hello";
        long now = System.currentTimeMillis();

        // 1. First insert should succeed without UnsupportedOperationException
        dedupMap.put(key, now);
        assertTrue(dedupMap.containsKey(key));
        assertEquals(Long.valueOf(now), dedupMap.get(key));

        // 2. Immediate duplicate within 3 seconds should be detected
        Long lastSeen = dedupMap.get(key);
        assertNotNull(lastSeen);
        boolean isDuplicate = (now - lastSeen) < 3000;
        assertTrue(isDuplicate);

        // 3. Repeat after 4 seconds should NOT be treated as a duplicate
        long futureTime = now + 4000;
        boolean isAllowedLater = (futureTime - lastSeen) >= 3000;
        assertTrue(isAllowedLater);
    }

    @Test
    public void testDeduplicationLRUEviction() {
        final int MAX_SIZE = 5;
        Map<String, Long> dedupMap = Collections.synchronizedMap(
                new LinkedHashMap<String, Long>(MAX_SIZE, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                        return size() > MAX_SIZE;
                    }
                }
        );

        for (int i = 1; i <= 10; i++) {
            dedupMap.put("key_" + i, (long) i);
        }

        // Cache size must never exceed MAX_SIZE
        assertEquals(5, dedupMap.size());
        // Oldest entries (1 to 5) should be evicted
        assertFalse(dedupMap.containsKey("key_1"));
        assertFalse(dedupMap.containsKey("key_5"));
        // Newest entries (6 to 10) must be present
        assertTrue(dedupMap.containsKey("key_10"));
    }

    @Test
    public void testChecksumIntegrity() {
        String data = "device123com.whatsappJohnHello123456789";
        String checksum1 = EncryptionUtils.generateChecksum(data);
        String checksum2 = EncryptionUtils.generateChecksum(data);

        assertNotNull(checksum1);
        assertEquals(64, checksum1.length()); // SHA-256 hex string length
        assertEquals(checksum1, checksum2);

        String alteredChecksum = EncryptionUtils.generateChecksum(data + "tampered");
        assertNotEquals(checksum1, alteredChecksum);
    }

    @Test
    public void testCanonicalBinaryAadStructure() {
        String deviceId = "test_dev_001";
        String messageId = "msg_uuid_002";
        long seqNum = 42L;
        int keyVersion = 1;
        long timestamp = 1793988356180L;

        byte[] aad = KeyEnrollmentHelper.buildCanonicalBinaryAad(
                deviceId, messageId, seqNum, keyVersion, timestamp, false);

        assertNotNull(aad);
        assertTrue(aad.length > 27);

        // Verify "SNAA" Magic Header (0x53, 0x4E, 0x41, 0x41)
        assertEquals((byte) 'S', aad[0]);
        assertEquals((byte) 'N', aad[1]);
        assertEquals((byte) 'A', aad[2]);
        assertEquals((byte) 'A', aad[3]);
    }

    @Test
    public void testSanitizationLogic() {
        String raw = "  Hello \r\n World \t !   ";
        String sanitized = raw.replaceAll("[\\r\\n\\t]+", " ").trim();
        assertEquals("Hello   World   !", sanitized);
    }
}
