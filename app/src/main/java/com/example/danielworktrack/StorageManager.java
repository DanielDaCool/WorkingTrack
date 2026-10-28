package com.example.danielworktrack;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles local persistence of active shift timers and cached dropdown locations using SharedPreferences.
 */
public class StorageManager {

    private static final String PREF_NAME = "WorkTrackPrefs";
    private static final String KEY_CLOCK_IN_TIME = "clockInTime";
    private static final String KEY_CACHED_PLACES = "cachedPlaces";
    private static final String KEY_STUDENT_COUNT = "studentCount";

    private final SharedPreferences prefs;

    public StorageManager(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public void saveClockInTime(long timestamp) {
        prefs.edit().putLong(KEY_CLOCK_IN_TIME, timestamp).apply();
    }

    public long getClockInTime() {
        return prefs.getLong(KEY_CLOCK_IN_TIME, -1);
    }

    public void clearActiveShift() {
        prefs.edit().putLong(KEY_CLOCK_IN_TIME, -1).remove(KEY_STUDENT_COUNT).apply();
    }

    /** Student count entered during the active shift; empty string clears it. */
    public void saveStudentCount(String count) {
        if (count == null || count.trim().isEmpty()) {
            prefs.edit().remove(KEY_STUDENT_COUNT).apply();
        } else {
            prefs.edit().putString(KEY_STUDENT_COUNT, count.trim()).apply();
        }
    }

    /** Returns the saved student count for the active shift, or "" if none. */
    public String getStudentCount() {
        return prefs.getString(KEY_STUDENT_COUNT, "");
    }

    public void savePlaces(String jsonArrayString) {
        prefs.edit().putString(KEY_CACHED_PLACES, jsonArrayString).apply();
    }

    /**
     * Retrieves cached place options or falls back to sensible defaults if offline/empty.
     */
    public List<String> getPlaces() {
        List<String> places = new ArrayList<>();
        String jsonString = prefs.getString(KEY_CACHED_PLACES, null);

        if (jsonString != null) {
            try {
                JSONArray array = new JSONArray(jsonString);
                for (int i = 0; i < array.length(); i++) {
                    places.add(array.getString(i));
                }
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        // Must match the "Places" sheet exactly, or the report sync won't recognize the place.
        if (places.isEmpty()) {
            places.add("תל אביב (fll)");
            places.add("ברנר");
            places.add("תל אביב (frc)");
            places.add("נס ציונה (בן צבי)");
            places.add("נס ציונה (שקד)");
            places.add("נס ציונה (ארגמן)");
        }

        return places;
    }
}