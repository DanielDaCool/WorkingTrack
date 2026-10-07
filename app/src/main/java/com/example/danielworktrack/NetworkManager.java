package com.example.danielworktrack;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.util.concurrent.TimeUnit;

/**
 * Manages all HTTP communication with the Google Apps Script backend using OkHttp.
 */
public class NetworkManager {

        private static final String WEB_APP_URL = "https://script.google.com/macros/s/AKfycbwqZIcXnamoGiKlBsBfHhuCzquALyayEG4n4Sh8gI5PtwPdVOJ7Xw5DXxGh46DOE5fV/exec";
    // Apps Script can take well over OkHttp's default 10s to answer (cold start).
    // A timeout makes WorkManager retry a POST the script already saved, which
    // duplicated rows in the sheet, so wait long enough for a real answer.
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    /**
     * Asynchronously fetches the list of available work locations from the backend.
     *
     * @param callback OkHttp callback to handle the response stream.
     */
    public void fetchPlaces(Callback callback) {
        Request request = new Request.Builder()
                .url(WEB_APP_URL)
                .build();
        client.newCall(request).enqueue(callback);
    }

    /**
     * Synchronously executes a shift submission POST request (used by WorkManager).
     *
     * @param jsonPayload The serialized JSON string representing the shift data.
     * @return True if the server responded with a 2xx success code, false otherwise.
     */
    public boolean syncShiftSync(String jsonPayload) {
        try {
            MediaType mediaType = MediaType.parse("application/json; charset=utf-8");
            RequestBody body = RequestBody.create(jsonPayload, mediaType);
            Request request = new Request.Builder()
                    .url(WEB_APP_URL)
                    .post(body)
                    .build();

            try (Response response = client.newCall(request).execute()) {
                return response.isSuccessful();
            }
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
}