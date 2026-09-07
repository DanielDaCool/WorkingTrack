package com.example.danielworktrack;

import android.app.TimePickerDialog;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;

/**
 * Primary activity managing shift lifecycle states, user interaction, and background synchronization feedback.
 */
public class MainActivity extends AppCompatActivity {

    private StorageManager storageManager;
    private NetworkManager networkManager;
    private TextView tvStatus;
    private Button btnAction;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        storageManager = new StorageManager(this);
        networkManager = new NetworkManager();

        tvStatus = findViewById(R.id.tvStatus);
        btnAction = findViewById(R.id.btnAction);

        TextView tvGreeting = findViewById(R.id.tvGreeting);
        if (tvGreeting != null) {
            tvGreeting.setText(getGreeting());
        }

        updateUIState();
        fetchPlacesDynamic();

        btnAction.setOnClickListener(v -> {
            if (storageManager.getClockInTime() == -1) {
                storageManager.saveClockInTime(System.currentTimeMillis());
                updateUIState();
                Toast.makeText(this, "Clocked In Successfully", Toast.LENGTH_SHORT).show();
            } else {
                showCheckoutDialog();
            }
        });
    }

    private void updateUIState() {
        long clockInTime = storageManager.getClockInTime();
        ImageView ivStatusIcon = findViewById(R.id.ivStatusIcon);
        
        if (clockInTime == -1) {
            tvStatus.setText("Clocked Out");
            btnAction.setText("Clock In");
            btnAction.setBackgroundResource(R.drawable.button_gradient_primary);
            btnAction.setBackgroundTintList(null);
            if (ivStatusIcon != null) {
                ivStatusIcon.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.md_theme_primary)));
            }
        } else {
            String timeString = dateFormat.format(clockInTime);
            tvStatus.setText("Clocked in at " + timeString);
            btnAction.setText("Clock Out");
            btnAction.setBackgroundResource(R.drawable.button_gradient_error);
            btnAction.setBackgroundTintList(null);
            if (ivStatusIcon != null) {
                ivStatusIcon.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.md_theme_error)));
            }
        }
    }

    private void fetchPlacesDynamic() {
        networkManager.fetchPlaces(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                // Silently fallback to cached local places
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful() && response.body() != null) {
                    storageManager.savePlaces(response.body().string());
                }
            }
        });
    }

    private void showCheckoutDialog() {
        BottomSheetDialog bottomSheet = new BottomSheetDialog(this);
        bottomSheet.setContentView(R.layout.layout_bottom_sheet_confirm);
        bottomSheet.setCancelable(false);

        TextView tvEntry = bottomSheet.findViewById(R.id.tvEntryTime);
        TextView tvLeave = bottomSheet.findViewById(R.id.tvLeaveTime);
        Spinner spinner = bottomSheet.findViewById(R.id.spinnerPlaces);
        Button btnSubmit = bottomSheet.findViewById(R.id.btnSubmit);
        TextView tvDuration = bottomSheet.findViewById(R.id.tvDurationPreview);
        TextView tvError = bottomSheet.findViewById(R.id.tvTimeError);
        LinearProgressIndicator progressShift = bottomSheet.findViewById(R.id.progressShift);

        Calendar entryCal = Calendar.getInstance();
        entryCal.setTimeInMillis(storageManager.getClockInTime());

        Calendar leaveCal = Calendar.getInstance();
        leaveCal.setTimeInMillis(System.currentTimeMillis());

        if (tvEntry != null && tvLeave != null && spinner != null && btnSubmit != null && tvDuration != null && tvError != null && progressShift != null) {
            tvEntry.setText(dateFormat.format(entryCal.getTime()));
            tvLeave.setText(dateFormat.format(leaveCal.getTime()));

            // Initial validation check
            updateValidationState(entryCal, leaveCal, tvDuration, tvError, progressShift, btnSubmit);

            // Load whatever is currently cached first
            List<String> places = storageManager.getPlaces();
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, places);
            spinner.setAdapter(adapter);

            // Fetch live places immediately when dialog opens to guarantee latest data
            networkManager.fetchPlaces(new okhttp3.Callback() {
                @Override
                public void onFailure(okhttp3.Call call, IOException e) {
                    // Keep cached places
                }

                @Override
                public void onResponse(okhttp3.Call call, okhttp3.Response response) throws IOException {
                    if (response.isSuccessful() && response.body() != null) {
                        String responseData = response.body().string();
                        storageManager.savePlaces(responseData);

                        runOnUiThread(() -> {
                            adapter.clear();
                            adapter.addAll(storageManager.getPlaces());
                            adapter.notifyDataSetChanged();
                        });
                    }
                }
            });

            tvEntry.setOnClickListener(v -> showTimePicker(entryCal, tvEntry, () -> 
                    updateValidationState(entryCal, leaveCal, tvDuration, tvError, progressShift, btnSubmit)));
            tvLeave.setOnClickListener(v -> showTimePicker(leaveCal, tvLeave, () -> 
                    updateValidationState(entryCal, leaveCal, tvDuration, tvError, progressShift, btnSubmit)));

            btnSubmit.setOnClickListener(v -> {
                String selectedPlace = spinner.getSelectedItem() != null ? spinner.getSelectedItem().toString() : "Default Office";
                submitShift(entryCal.getTimeInMillis(), leaveCal.getTimeInMillis(), selectedPlace);
                bottomSheet.dismiss();
                storageManager.clearActiveShift();
                updateUIState();
            });
        }

        bottomSheet.show();
    }

    private void updateValidationState(Calendar entry, Calendar leave, TextView tvDuration, TextView tvError, LinearProgressIndicator progress, Button btnSubmit) {
        long durationMs = leave.getTimeInMillis() - entry.getTimeInMillis();
        long now = System.currentTimeMillis();

        boolean isFuture = entry.getTimeInMillis() > now || leave.getTimeInMillis() > now;
        boolean isNegative = durationMs <= 0;

        if (isNegative) {
            tvDuration.setText("0.00 hours");
            tvError.setText("Leave time must be after entry time");
            tvError.setVisibility(android.view.View.VISIBLE);
            progress.setProgress(0);
            btnSubmit.setEnabled(false);
            btnSubmit.setAlpha(0.5f);
        } else if (isFuture) {
            tvDuration.setText("0.00 hours");
            tvError.setText("Shift times cannot be in the future");
            tvError.setVisibility(android.view.View.VISIBLE);
            progress.setProgress(0);
            btnSubmit.setEnabled(false);
            btnSubmit.setAlpha(0.5f);
        } else {
            double durationHours = durationMs / (1000.0 * 60.0 * 60.0);
            tvDuration.setText(String.format(Locale.US, "%.2f hours", durationHours));
            tvError.setVisibility(android.view.View.GONE);
            
            // Progress based on 8 hour workday
            int progressVal = (int) ((durationHours / 8.0) * 100);
            progress.setProgress(Math.min(progressVal, 100));
            
            btnSubmit.setEnabled(true);
            btnSubmit.setAlpha(1.0f);
        }
    }

    private String getGreeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (hour >= 5 && hour < 12) return "Good Morning,";
        if (hour >= 12 && hour < 17) return "Good Afternoon,";
        if (hour >= 17 && hour < 21) return "Good Evening,";
        return "Good Night,";
    }

    private void showTimePicker(Calendar calendar, TextView targetView, Runnable onTimeSet) {
        new TimePickerDialog(this, (view, hourOfDay, minute) -> {
            calendar.set(Calendar.HOUR_OF_DAY, hourOfDay);
            calendar.set(Calendar.MINUTE, minute);
            targetView.setText(dateFormat.format(calendar.getTime()));
            if (onTimeSet != null) onTimeSet.run();
        }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), true).show();
    }

    private void submitShift(long entryTime, long leaveTime, String place) {
        if (leaveTime <= entryTime) {
            Toast.makeText(this, "Invalid shift: duration must be positive", Toast.LENGTH_SHORT).show();
            return;
        }

        double durationMs = leaveTime - entryTime;
        double durationHours = durationMs / (1000.0 * 60.0 * 60.0);
        String formattedDuration = String.format(Locale.US, "%.2f", durationHours);

        JSONObject payload = new JSONObject();
        try {
            payload.put("entryTime", dateFormat.format(entryTime));
            payload.put("leaveTime", dateFormat.format(leaveTime));
            payload.put("place", place);
            payload.put("duration", formattedDuration);
        } catch (JSONException e) {
            e.printStackTrace();
            return;
        }

        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        Data inputData = new Data.Builder()
                .putString("payload", payload.toString())
                .build();

        OneTimeWorkRequest syncRequest = new OneTimeWorkRequest.Builder(ShiftSyncWorker.class)
                .setConstraints(constraints)
                .setInputData(inputData)
                .build();

        WorkManager workManager = WorkManager.getInstance(this);
        workManager.enqueue(syncRequest);

        // Accurately observe sync progress to give precise feedback without premature messaging
        workManager.getWorkInfoByIdLiveData(syncRequest.getId()).observe(this, workInfo -> {
            if (workInfo != null) {
                WorkInfo.State state = workInfo.getState();
                if (state.isFinished()) {
                    if (state == WorkInfo.State.SUCCEEDED) {
                        Toast.makeText(this, "Shift successfully saved to Sheets!", Toast.LENGTH_LONG).show();
                    } else if (state == WorkInfo.State.FAILED) {
                        Toast.makeText(this, "Network error: Shift queued for retry.", Toast.LENGTH_LONG).show();
                    }
                }
            }
        });

        Toast.makeText(this, "Processing shift submission...", Toast.LENGTH_SHORT).show();
    }
}