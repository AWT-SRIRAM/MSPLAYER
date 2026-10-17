package app.msplayer.offline;
import androidx.annotation.NonNull;
import androidx.room.Entity;
@Entity(tableName="playlist_entries",primaryKeys={"playlist","uri"})
public class PlaylistEntry {
    @NonNull public String playlist = "";
    @NonNull public String uri = "";
    @NonNull public String title = "";
    public long added;
    public PlaylistEntry() {}
}
