package app.msplayer.offline;
import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;
@Entity(tableName="saved_media", indices = {@Index("updated")})
public class SavedMedia {
    @PrimaryKey @NonNull public String uri = "";
    @NonNull public String title = "";
    public long position;
    public long updated;
    public boolean watched;
    public SavedMedia() {}
}
