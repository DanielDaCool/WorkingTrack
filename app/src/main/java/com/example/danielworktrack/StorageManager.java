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
        prefs.edit().putLong(KEY_CLOCK_IN_TIME, -1).apply();
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

        if (places.isEmpty()) {
            places.add("Main Office");
            places.add("Remote / Home");
        }

        return places;
    }
}