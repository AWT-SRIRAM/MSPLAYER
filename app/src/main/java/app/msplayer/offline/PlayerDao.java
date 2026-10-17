package app.msplayer.offline;
import androidx.room.*;
import java.util.List;
@Dao public interface PlayerDao {
    @Query("SELECT * FROM saved_media ORDER BY updated DESC") List<SavedMedia> history();
    @Query("SELECT * FROM saved_media WHERE uri=:uri") SavedMedia saved(String uri);
    @Insert(onConflict=OnConflictStrategy.REPLACE) void save(SavedMedia item);
    @Query("DELETE FROM saved_media") void clearHistory();
    @Insert(onConflict=OnConflictStrategy.REPLACE) void add(PlaylistEntry item);
    @Query("SELECT DISTINCT playlist FROM playlist_entries ORDER BY playlist") List<String> playlists();
    @Query("SELECT * FROM playlist_entries WHERE playlist=:name ORDER BY added") List<PlaylistEntry> entries(String name);
    @Query("DELETE FROM playlist_entries WHERE playlist=:name AND uri=:uri") void remove(String name,String uri);
    @Query("DELETE FROM playlist_entries WHERE playlist=:name") void deletePlaylist(String name);
}
