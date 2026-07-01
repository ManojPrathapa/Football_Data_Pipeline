
import java.util.*;

/**
 * Exact schema of Europe_-_Champions_League.csv (StatsBomb export), 122 columns,
 * ';' delimited, matches the NiFi CSVReader controller service (csv-header-derived).
 *
 * FieldType classification drives which mutation operators are legal for a column,
 * so mutated rows stay column-count-valid (syntax-aware) even when the VALUE inside
 * a column is deliberately broken.
 */
public class CsvSchema {

    public enum FieldType { INTEGER, DOUBLE, BOOLEAN, EVENT_TYPE, STRING, COMPLEX }

    public static final String[] COLUMNS = {
        "50_50","ball_receipt_outcome","ball_recovery_recovery_failure","carry_end_location",
        "clearance_aerial_won","counterpress","dribble_nutmeg","dribble_outcome","dribble_overrun",
        "duel_outcome","duel_type","duration","foul_committed_advantage","foul_committed_card",
        "foul_won_advantage","foul_won_defensive","goalkeeper_body_part","goalkeeper_end_location",
        "goalkeeper_outcome","goalkeeper_position","goalkeeper_technique","goalkeeper_type","id",
        "index","interception_outcome","location","match_id","minute","pass_aerial_won","pass_angle",
        "pass_assisted_shot_id","pass_backheel","pass_body_part","pass_cross","pass_cut_back",
        "pass_deflected","pass_end_location","pass_goal_assist","pass_height","pass_length",
        "pass_outcome","pass_recipient","pass_recipient_id","pass_shot_assist","pass_switch",
        "pass_type","period","play_pattern","player","player_id","position","possession",
        "possession_team","possession_team_id","related_events","second","shot_aerial_won",
        "shot_body_part","shot_end_location","shot_first_time","shot_freeze_frame","shot_key_pass_id",
        "shot_one_on_one","shot_outcome","shot_statsbomb_xg","shot_technique","shot_type",
        "substitution_outcome","substitution_outcome_id","substitution_replacement",
        "substitution_replacement_id","tactics","team","team_id","timestamp","type","under_pressure",
        "competition","season","block_offensive","foul_committed_offensive","foul_committed_type",
        "injury_stoppage_in_chain","pass_miscommunication","pass_technique","pass_through_ball",
        "block_deflection","ball_recovery_offensive","foul_committed_penalty","foul_won_penalty",
        "shot_open_goal","shot_redirect","bad_behaviour_card","block_save_block","shot_deflected",
        "miscontrol_aerial_won","clearance_body_part","clearance_head","clearance_left_foot",
        "clearance_right_foot","goalkeeper_punched_out","off_camera","out","pass_inswinging",
        "pass_outswinging","pass_straight","goalkeeper_shot_saved_to_post","pass_no_touch",
        "shot_saved_to_post","goalkeeper_success_in_play","clearance_other","player_off_permanent",
        "goalkeeper_shot_saved_off_target","shot_saved_off_target","shot_follows_dribble",
        "dribble_no_touch","goalkeeper_lost_out","half_start_late_video_start",
        "goalkeeper_lost_in_play","goalkeeper_penalty_saved_to_post","goalkeeper_saved_to_post",
        "goalkeeper_success_out"
    };

    public static final Map<String, Integer> INDEX = new HashMap<>();
    public static final Map<String, FieldType> TYPES = new HashMap<>();

    // Fields the Jolt specs explicitly cast with =toInteger / =toDouble / =toBoolean.
    // These are the highest-value fuzz targets: a bad value here breaks Jolt or the DB insert.
    private static final String[] INT_FIELDS = {
        "index","match_id","player_id","team_id","possession","period","minute","second",
        "possession_team_id","pass_recipient_id"
    };
    private static final String[] DOUBLE_FIELDS = { "duration","shot_statsbomb_xg","pass_angle","pass_length" };
    private static final String[] BOOL_FIELDS = {
        "shot_aerial_won","shot_first_time","shot_one_on_one","under_pressure","shot_redirect",
        "shot_deflected","out","dribble_nutmeg","dribble_overrun","pass_aerial_won","pass_backheel",
        "pass_cross","pass_cut_back","pass_deflected","pass_goal_assist","pass_shot_assist",
        "pass_switch","counterpress","pass_miscommunication","pass_through_ball","pass_inswinging",
        "pass_outswinging","pass_straight"
    };
    private static final String[] COMPLEX_FIELDS = {
        "location","carry_end_location","pass_end_location","shot_end_location",
        "goalkeeper_end_location","tactics","shot_freeze_frame","related_events"
    };

    static {
        for (int idx = 0; idx < COLUMNS.length; idx++) INDEX.put(COLUMNS[idx], idx);
        for (String c : COLUMNS) TYPES.put(c, FieldType.STRING);
        for (String c : INT_FIELDS) TYPES.put(c, FieldType.INTEGER);
        for (String c : DOUBLE_FIELDS) TYPES.put(c, FieldType.DOUBLE);
        for (String c : BOOL_FIELDS) TYPES.put(c, FieldType.BOOLEAN);
        for (String c : COMPLEX_FIELDS) TYPES.put(c, FieldType.COMPLEX);
        TYPES.put("type", FieldType.EVENT_TYPE);
    }

    public static int col(String name) {
        Integer v = INDEX.get(name);
        if (v == null) throw new IllegalArgumentException("Unknown column: " + name);
        return v;
    }

    public static List<Integer> columnsOfType(FieldType t) {
        List<Integer> out = new ArrayList<>();
        for (String c : COLUMNS) if (TYPES.get(c) == t) out.add(INDEX.get(c));
        return out;
    }

    public static String header() { return String.join(";", COLUMNS); }

    // The three event types the pipeline actually routes downstream (RouteOnAttribute).
    public static final String[] ROUTED_TYPES = { "Pass", "Shot", "Dribble" };
}
