package org.ddport.darkest;

import android.content.SharedPreferences;
import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The game's achievements, kept by the app the way Steam keeps them: once done in any campaign, done
 * on this phone. The game's own tracker reports each one it finds completed (native/ddglue,
 * "achievements"), including all a campaign has done when it loads. So before the game starts, every
 * save's completed achievements are taken in silently (addSaves), and only a report of one not done
 * yet pops up (reported). The three party-wipe achievements never reach the saves (the game keeps no
 * "completed" for them), so those count from their report only.
 * The titles and descriptions come from the game's own string table, which GOG 26186 doesn't have;
 * there an achievement shows its id, made readable.
 */
final class Achievements {
    private static final String TAG = "DDActivity";
    private static final String DONE = "achievements";   // the ids done, in the preferences
    /**
     * Each achievement's id in the game and its key in the game's string table, in the game's order
     * (the order of both the save and the string table, except r2 and r3, which the table swaps).
     */
    private static final String[] IDS_AND_KEYS = {
            "welcome_home", "q1", "first_victory", "q2", "ten_victories", "q3", "twenty_five_victories", "q4",
            "abandon_quest", "q5", "first_boss", "q6", "medium_quest", "q7", "long_quest", "q8",
            "start_quest_heroes_level_6", "q9", "start_dd_heroes_level_0", "q10", "all_regions", "q11",
            "four_afflicted", "q12", "single_combat_skill", "t1", "single_camping_skill", "t2",
            "tavern_treatment", "t3", "abbey_treatment", "t4", "hero_dismissed", "t5",
            "one_of_each_hero", "t6", "quirk_treatment", "t7", "quirk_lock", "t8",
            "blacksmith_upgraded", "t9", "guild_upgraded", "t10", "tavern_upgraded", "t11",
            "abbey_upgraded", "t12", "weapon_and_armour_upgraded", "t13", "hero_4_skill_upgrade", "t14",
            "town_fully_upgraded", "t15", "visit_graveyard", "t16", "one_hero_level_6", "s1",
            "four_hero_level_6", "s2", "eldritch_100", "s3", "unholy_100", "s4", "beast_100", "s5",
            "human_100", "s6", "total_enemies", "s7", "week_52", "s8", "week_104", "s9",
            "quest_20k_gold", "s10", "quest_30_heirloom", "s11", "kill_50", "s12", "walk_500", "s13",
            "lost_first_hero", "d1", "first_heart_attack_death", "d2", "first_hunger_death", "d3",
            "death_to_wilbur", "d4", "death_to_maggot", "d5", "first_trap_death", "d6",
            "first_obstacle_death", "d7", "darkest_dungeon_party_wipe", "d8", "first_party_wipe", "d9",
            "lose_level_6_hero", "d10", "deaths_door_survived_5_attacks", "d11",
            "first_boss_party_wipe", "d12", "ancestors_trinkets", "o1", "slay_a_shambler", "o2",
            "game_complete_on_default", "o3", "kill_all_bosses", "o4", "caretaker_goals_complete", "o5",
            "game_complete", "o6", "game_complete_in_75_weeks", "o7", "kill_boss_with_one_hero", "o8",
            "kill_boss_with_four_afflicted", "o9", "game_complete_ng", "o10",
            "dismas_and_reynauld_completed", "o11", "defeat_the_brigand_vulf", "o12",
            "game_complete_radiant", "r4", "slay_4_champions", "r3", "slay_a_crow", "r2",
            "slay_a_squiffy_with_jester", "r1", "slay_the_baron", "CC1", "slay_the_viscount", "CC2",
            "slay_the_countess", "CC3", "get_crimson_curse", "CC4", "collect_trinket_set", "CC5",
            "build_a_district_building", "CC6", "build_the_red_hook", "CC7", "recruit_a_flagellant", "CC8",
            "collect_the_invitation", "CC9", "lose_a_cursed_hero", "CC10",
            "kill_an_ally_through_curse", "CC11", "get_crimson_curse_via_contagion", "CC12",
            "slay_the_fanatic", "CC13", "rescue_a_prisoner", "CC14", "entire_roster_has_curse", "CC15",
            "plowshares_to_swords", "COM1", "lost_in_dust", "COM2", "taste_of_madness", "COM3",
            "merciful_act", "COM4", "defeat_sleeper", "COM5", "shards_well_spent", "COM6",
            "build_windmill", "COM7", "this_is_nothing", "COM8", "hollow_reckoning", "COM9",
            "ashes_to_ashes", "COM10", "mouth_of_madness", "COM11", "blinders_lifted", "COM12",
            "rogues_gallery", "COM13", "defeat_thing_from_stars", "COM14", "revenant_riposte", "COM15",
            "blue_skies_ahead", "COM16", "four_hero_dodge_miller", "COM17", "lining_pocket", "COM18",
            "rainbows_end", "COM19", "pipe_dream", "COM20",
    };
    static final int COUNT = IDS_AND_KEYS.length / 2;

    /** One achievement as the list shows it. */
    static final class Entry {
        final String section, title, description;
        final boolean done;

        Entry(String section, String title, String description, boolean done) {
            this.section = section;
            this.title = title;
            this.description = description;
            this.done = done;
        }
    }

    private final SharedPreferences prefs;
    private final File stringTable;
    private final Set<String> done;
    private Map<String, String> text;   // the string table's English entries, read on first use

    /** stringTable: the game's localization/achievements.string_table.xml (it may be missing) */
    Achievements(SharedPreferences prefs, File stringTable) {
        this.prefs = prefs;
        this.stringTable = stringTable;
        done = new HashSet<>(prefs.getStringSet(DONE, new HashSet<>()));
    }

    /** Takes in, without popping up, every achievement the campaigns in the save folder have completed. */
    void addSaves(File saves) {
        File[] profiles = saves.listFiles((dir, name) -> name.matches("profile_\\d+"));
        if (profiles == null)
            return;
        int before = done.size();
        for (File profile : profiles) {
            File progression = new File(profile, "persist.progression.json");
            if (!progression.isFile())
                continue;
            try {
                SaveFile save = new SaveFile(progression);
                for (int i = 0; i < IDS_AND_KEYS.length; i += 2)
                    if (save.flag("base_root.real_achievements." + IDS_AND_KEYS[i] + ".completed"))
                        done.add(IDS_AND_KEYS[i]);
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "achievements: cannot read " + progression + ": " + e);
            }
        }
        if (done.size() != before)
            save();
        Log.i(TAG, "achievements: " + done.size() + " done, " + (done.size() - before) + " new from the saves");
    }

    /** The game reports an achievement done: its title and description to pop up, or null if it was done already. */
    String[] reported(String id) {
        if (!done.add(id))
            return null;
        save();
        String key = key(id);
        return new String[] { title(id, key), description(key) };
    }

    int doneCount() {
        int n = 0;
        for (int i = 0; i < IDS_AND_KEYS.length; i += 2)
            if (done.contains(IDS_AND_KEYS[i]))
                n++;
        return n;
    }

    List<Entry> list() {
        List<Entry> list = new ArrayList<>(COUNT);
        for (int i = 0; i < IDS_AND_KEYS.length; i += 2) {
            String id = IDS_AND_KEYS[i], key = IDS_AND_KEYS[i + 1];
            String section = key.startsWith("COM") ? "The Color of Madness"
                    : key.startsWith("CC") ? "The Crimson Court" : "Darkest Dungeon";
            list.add(new Entry(section, title(id, key), description(key), done.contains(id)));
        }
        return list;
    }

    private void save() {
        prefs.edit().putStringSet(DONE, new HashSet<>(done)).apply();
    }

    private static String key(String id) {
        for (int i = 0; i < IDS_AND_KEYS.length; i += 2)
            if (IDS_AND_KEYS[i].equals(id))
                return IDS_AND_KEYS[i + 1];
        return null;   // a game build with an achievement this table doesn't know
    }

    private String title(String id, String key) {
        String t = key != null ? text().get("str_achievement_title_" + key) : null;
        if (t != null)
            return t;
        String readable = id.replace('_', ' ');   // "welcome_home": "Welcome home"
        return Character.toUpperCase(readable.charAt(0)) + readable.substring(1);
    }

    private String description(String key) {
        String d = key != null ? text().get("str_achievement_description_" + key) : null;
        return d != null ? d : "";
    }

    /** The English entries of the game's string table: <language id="english"><entry id="…">text</entry>… */
    private Map<String, String> text() {
        if (text != null)
            return text;
        text = new HashMap<>();
        if (!stringTable.isFile())
            return text;
        try (InputStream in = new FileInputStream(stringTable)) {
            XmlPullParser p = Xml.newPullParser();
            p.setInput(in, "UTF-8");
            boolean english = false;
            for (int e = p.getEventType(); e != XmlPullParser.END_DOCUMENT; e = p.next()) {
                if (e == XmlPullParser.START_TAG && p.getName().equals("language"))
                    english = "english".equals(p.getAttributeValue(null, "id"));
                else if (e == XmlPullParser.END_TAG && p.getName().equals("language") && english)
                    break;
                else if (e == XmlPullParser.START_TAG && english && p.getName().equals("entry"))
                    text.put(p.getAttributeValue(null, "id"), p.nextText().trim());
            }
        } catch (Exception e) {
            Log.w(TAG, "achievements: cannot read " + stringTable + ": " + e);
        }
        return text;
    }
}
