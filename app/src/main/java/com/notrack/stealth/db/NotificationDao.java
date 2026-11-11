package com.notrack.stealth.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface NotificationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(NotificationEntity entity);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    List<Long> insertAll(List<NotificationEntity> entities);

    @Update
    void update(NotificationEntity entity);

    @Query("SELECT * FROM notifications_log WHERE sync_status = 0 ORDER BY timestamp ASC LIMIT 50")
    List<NotificationEntity> getUnsyncedNotifications();

    @Query("SELECT * FROM notifications_log WHERE sync_status = 0 ORDER BY timestamp ASC LIMIT :limit")
    List<NotificationEntity> getUnsyncedBatch(int limit);

    @Query("UPDATE notifications_log SET sync_status = 1 WHERE id IN (:ids)")
    void markAsSynced(List<Long> ids);

    @Query("SELECT COUNT(*) FROM notifications_log WHERE sync_status = 0")
    int getUnsyncedCount();

    @Query("SELECT COUNT(*) FROM notifications_log WHERE sync_status = 1")
    int getSyncedCount();

    @Query("SELECT COUNT(*) FROM notifications_log")
    int getTotalCount();

    @Query("SELECT * FROM notifications_log ORDER BY timestamp DESC LIMIT :limit")
    List<NotificationEntity> getRecentNotifications(int limit);

    @Query("SELECT * FROM notifications_log ORDER BY id ASC")
    List<NotificationEntity> getAllNotifications();

    @Query("DELETE FROM notifications_log")
    void deleteAll();
}
