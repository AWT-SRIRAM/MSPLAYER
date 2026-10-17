package app.msplayer.offline;
import androidx.room.*;
@Database(entities={SavedMedia.class,PlaylistEntry.class},version=1,exportSchema=false)
public abstract class PlayerDatabase extends RoomDatabase { public abstract PlayerDao dao(); }
