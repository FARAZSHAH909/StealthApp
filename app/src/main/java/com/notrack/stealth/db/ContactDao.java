package com.notrack.stealth.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface ContactDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(ContactEntity entity);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    List<Long> insertAll(List<ContactEntity> entities);

    @Update
    void update(ContactEntity entity);

    @Query("SELECT * FROM contacts_vault WHERE sync_status = 0 LIMIT :limit")
    List<ContactEntity> getUnsyncedContacts(int limit);

    @Query("UPDATE contacts_vault SET sync_status = 1 WHERE id IN (:ids)")
    void markAsSynced(List<Long> ids);

    @Query("SELECT contact_id FROM contacts_vault WHERE is_deleted = 0")
    List<String> getActiveContactIds();

    @Query("UPDATE contacts_vault SET is_deleted = 1, deleted_at = :deletedAt, sync_status = 0 WHERE contact_id IN (:deletedIds)")
    void markContactsAsDeleted(List<String> deletedIds, long deletedAt);

    @Query("SELECT COUNT(*) FROM contacts_vault WHERE contact_id = :contactId LIMIT 1")
    int exists(String contactId);

    @Query("SELECT * FROM contacts_vault ORDER BY timestamp DESC LIMIT :limit")
    List<ContactEntity> getRecentContacts(int limit);
}
