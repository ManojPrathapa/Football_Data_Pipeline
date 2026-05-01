import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.nio.file.Paths;

public class CreateDb {
    public static void main(String[] args) throws Exception {

        // Explicitly load the SQLite driver from the JAR
        Class.forName("org.sqlite.JDBC");

        String dbPath = Paths.get(System.getProperty("user.dir"), "soccer_data.db").toString();
        String url = "jdbc:sqlite:" + dbPath;

        System.out.println("Creating database at: " + dbPath);

        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement()) {

            stmt.execute("PRAGMA journal_mode = WAL");
            stmt.execute("PRAGMA foreign_keys = ON");

            // ── SHOTS ──────────────────────────────────────────────────
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS shots (
                    id                  TEXT    PRIMARY KEY,
                    index_num           INTEGER NOT NULL,
                    match_id            INTEGER NOT NULL,
                    team_id             INTEGER NOT NULL,
                    team                TEXT    NOT NULL,
                    competition         TEXT    NOT NULL,
                    season              TEXT    NOT NULL,
                    player_id           INTEGER NOT NULL,
                    player              TEXT    NOT NULL,
                    position            TEXT    NOT NULL,
                    possession          INTEGER NOT NULL,
                    location            TEXT    NOT NULL,
                    period              INTEGER NOT NULL,
                    minute              INTEGER NOT NULL,
                    second              INTEGER NOT NULL,
                    duration            REAL    NOT NULL,
                    play_pattern        TEXT    NOT NULL,
                    shot_body_part      TEXT    NOT NULL,
                    shot_technique      TEXT    NOT NULL,
                    shot_type           TEXT    NOT NULL,
                    shot_key_pass_id    TEXT,
                    shot_freeze_frame   TEXT,
                    shot_aerial_won     INTEGER NOT NULL DEFAULT 0,
                    shot_first_time     INTEGER NOT NULL DEFAULT 0,
                    shot_one_on_one     INTEGER NOT NULL DEFAULT 0,
                    under_pressure      INTEGER NOT NULL DEFAULT 0,
                    shot_redirect       INTEGER NOT NULL DEFAULT 0,
                    shot_deflected      INTEGER NOT NULL DEFAULT 0,
                    shot_statsbomb_xg   REAL    NOT NULL,
                    shot_end_location   TEXT    NOT NULL,
                    shot_outcome        TEXT    NOT NULL,
                    out                 INTEGER NOT NULL DEFAULT 0,
                    ingestion_ts        TEXT,
                    updated_at          TEXT    DEFAULT (datetime('now','utc'))
                )
            """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_shots_match   ON shots(match_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_shots_player  ON shots(player_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_shots_outcome ON shots(shot_outcome)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_shots_minute  ON shots(minute)");
            System.out.println("✓ shots table created");

            // ── PASSES ─────────────────────────────────────────────────
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS passes (
                    id                      TEXT    PRIMARY KEY,
                    index_num               INTEGER NOT NULL,
                    match_id                INTEGER NOT NULL,
                    team_id                 INTEGER NOT NULL,
                    team                    TEXT    NOT NULL,
                    competition             TEXT    NOT NULL,
                    season                  TEXT    NOT NULL,
                    player_id               INTEGER NOT NULL,
                    player                  TEXT    NOT NULL,
                    position                TEXT    NOT NULL,
                    possession              INTEGER NOT NULL,
                    location                TEXT    NOT NULL,
                    period                  INTEGER NOT NULL,
                    minute                  INTEGER NOT NULL,
                    second                  INTEGER NOT NULL,
                    duration                REAL    NOT NULL,
                    play_pattern            TEXT    NOT NULL,
                    pass_angle              REAL    NOT NULL,
                    pass_body_part          TEXT,
                    pass_height             TEXT    NOT NULL,
                    pass_length             REAL    NOT NULL,
                    pass_type               TEXT,
                    pass_technique          TEXT,
                    counterpress            INTEGER NOT NULL DEFAULT 0,
                    pass_aerial_won         INTEGER NOT NULL DEFAULT 0,
                    pass_backheel           INTEGER NOT NULL DEFAULT 0,
                    pass_cross              INTEGER NOT NULL DEFAULT 0,
                    pass_cut_back           INTEGER NOT NULL DEFAULT 0,
                    pass_deflected          INTEGER NOT NULL DEFAULT 0,
                    pass_switch             INTEGER NOT NULL DEFAULT 0,
                    under_pressure          INTEGER NOT NULL DEFAULT 0,
                    pass_miscommunication   INTEGER NOT NULL DEFAULT 0,
                    pass_through_ball       INTEGER NOT NULL DEFAULT 0,
                    pass_inswinging         INTEGER NOT NULL DEFAULT 0,
                    pass_outswinging        INTEGER NOT NULL DEFAULT 0,
                    pass_straight           INTEGER NOT NULL DEFAULT 0,
                    pass_recipient          TEXT,
                    pass_recipient_id       INTEGER,
                    pass_assisted_shot_id   TEXT,
                    pass_shot_assist        INTEGER NOT NULL DEFAULT 0,
                    pass_goal_assist        INTEGER NOT NULL DEFAULT 0,
                    pass_end_location       TEXT    NOT NULL,
                    pass_outcome            TEXT,
                    out                     INTEGER NOT NULL DEFAULT 0,
                    ingestion_ts            TEXT,
                    updated_at              TEXT    DEFAULT (datetime('now','utc'))
                )
            """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_passes_match     ON passes(match_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_passes_player    ON passes(player_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_passes_outcome   ON passes(pass_outcome)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_passes_recipient ON passes(pass_recipient_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_passes_minute    ON passes(minute)");
            System.out.println("✓ passes table created");

            // ── DRIBBLES ───────────────────────────────────────────────
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS dribbles (
                    id                  TEXT    PRIMARY KEY,
                    index_num           INTEGER NOT NULL,
                    match_id            INTEGER NOT NULL,
                    team_id             INTEGER NOT NULL,
                    team                TEXT    NOT NULL,
                    competition         TEXT    NOT NULL,
                    season              TEXT    NOT NULL,
                    player_id           INTEGER NOT NULL,
                    player              TEXT    NOT NULL,
                    position            TEXT    NOT NULL,
                    possession          INTEGER NOT NULL,
                    location            TEXT    NOT NULL,
                    period              INTEGER NOT NULL,
                    minute              INTEGER NOT NULL,
                    second              INTEGER NOT NULL,
                    duration            REAL    NOT NULL,
                    play_pattern        TEXT    NOT NULL,
                    dribble_nutmeg      INTEGER NOT NULL DEFAULT 0,
                    dribble_outcome     TEXT    NOT NULL,
                    dribble_overrun     INTEGER NOT NULL DEFAULT 0,
                    under_pressure      INTEGER NOT NULL DEFAULT 0,
                    out                 INTEGER NOT NULL DEFAULT 0,
                    ingestion_ts        TEXT,
                    updated_at          TEXT    DEFAULT (datetime('now','utc'))
                )
            """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_dribbles_match   ON dribbles(match_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_dribbles_player  ON dribbles(player_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_dribbles_outcome ON dribbles(dribble_outcome)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_dribbles_minute  ON dribbles(minute)");
            System.out.println("✓ dribbles table created");

            System.out.println("\nAll done! soccer_data.db is ready for NiFi.");
        }
    }
}