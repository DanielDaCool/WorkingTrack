package com.example.danielworktrack;

import android.app.TimePickerDialog;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
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
import com.google.android.material.chip.Chip;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;

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
        
        // Animated state transition
        btnAction.animate().scaleX(0.9f).scaleY(0.9f).setDuration(100).withEndAction(() -> {
            if (clockInTime == -1) {
                tvStatus.setText("Clocked Out");
                btnAction.setText("Clock In");
                btnAction.setBackgroundResource(R.drawable.button_gradient_primary);
                btnAction.setBackgroundTintList(null);
                if (ivStatusIcon != null) {
                    ivStatusIcon.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.md_theme_primary)));
                }
            } else {
                tvStatus.setText("Clocked in at " + dateFormat.format(clockInTime));
                btnAction.setText("Clock Out");
                btnAction.setBackgroundResource(R.drawable.button_gradient_error);
                btnAction.setBackgroundTintList(null);
                if (ivStatusIcon != null) {
                    ivStatusIcon.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.md_theme_error)));
                }
            }
            btnAction.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start();
        }).start();
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
        View cardEntry = bottomSheet.findViewById(R.id.cardEntry);
        View cardLeave = bottomSheet.findViewById(R.id.cardLeave);
        TextView btnToggleEdit = bottomSheet.findViewById(R.id.btnToggleEdit);
        AutoCompleteTextView actvPlaces = bottomSheet.findViewById(R.id.actvPlaces);
        TextInputEditText etNotes1 = bottomSheet.findViewById(R.id.etNotes1);
        TextInputEditText etNotes2 = bottomSheet.findViewById(R.id.etNotes2);
        Button btnSubmit = bottomSheet.findViewById(R.id.btnSubmit);
        TextView tvDuration = bottomSheet.findViewById(R.id.tvDurationPreview);
        TextView tvError = bottomSheet.findViewById(R.id.tvTimeError);
        LinearProgressIndicator progressShift = bottomSheet.findViewById(R.id.progressShift);

        Calendar entryCal = Calendar.getInstance();
        entryCal.setTimeInMillis(storageManager.getClockInTime());

        Calendar leaveCal = Calendar.getInstance();
        leaveCal.setTimeInMillis(System.currentTimeMillis());

        final boolean[] isEditMode = {false};

        if (tvEntry != null && tvLeave != null && cardEntry != null && cardLeave != null && btnToggleEdit != null && actvPlaces != null && etNotes1 != null && etNotes2 != null && btnSubmit != null && tvDuration != null && tvError != null && progressShift != null) {
            tvEntry.setText(dateFormat.format(entryCal.getTime()));
            tvLeave.setText(dateFormat.format(leaveCal.getTime()));

            // Initial validation check
            updateValidationState(entryCal, leaveCal, tvDuration, tvError, progressShift, btnSubmit);

            btnToggleEdit.setOnClickListener(v -> {
                isEditMode[0] = !isEditMode[0];
                btnToggleEdit.setText(isEditMode[0] ? "Disable Manual Edit" : "Adjust Times");
                cardEntry.setAlpha(isEditMode[0] ? 1.0f : 0.8f);
                cardLeave.setAlpha(isEditMode[0] ? 1.0f : 0.8f);
                Toast.makeText(this, isEditMode[0] ? "Manual editing enabled" : "Manual editing disabled", Toast.LENGTH_SHORT).show();
            });

            cardEntry.setOnClickListener(v -> {
                if (isEditMode[0]) {
                    showTimePicker(entryCal, tvEntry, () -> 
                        updateValidationState(entryCal, leaveCal, tvDuration, tvError, progressShift, btnSubmit));
                }
            });

            cardLeave.setOnClickListener(v -> {
                if (isEditMode[0]) {
                    showTimePicker(leaveCal, tvLeave, () -> 
                        updateValidationState(entryCal, leaveCal, tvDuration, tvError, progressShift, btnSubmit));
                }
            });

            // Load whatever is currently cached first
            List<String> places = storageManager.getPlaces();
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.item_spinner, places);
            actvPlaces.setAdapter(adapter);
            if (!places.isEmpty()) {
                actvPlaces.setText(places.get(0), false);
            }

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
                            if (actvPlaces.getText().toString().isEmpty() && !storageManager.getPlaces().isEmpty()) {
                                actvPlaces.setText(storageManager.getPlaces().get(0), false);
                            }
                        });
                    }
                }
            });

            btnSubmit.setOnClickListener(v -> {
                String selectedPlace = actvPlaces.getText().toString();
                if (selectedPlace.isEmpty()) selectedPlace = "Default Office";
                String notes1 = etNotes1.getText() != null ? etNotes1.getText().toString() : "";
                String notes2 = etNotes2.getText() != null ? etNotes2.getText().toString() : "";
                submitShift(entryCal.getTimeInMillis(), leaveCal.getTimeInMillis(), selectedPlace, notes1, notes2);
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
        MaterialTimePicker picker = new MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_24H)
                .setHour(calendar.get(Calendar.HOUR_OF_DAY))
                .setMinute(calendar.get(Calendar.MINUTE))
                .setTitleText("Select Time")
                .build();

        picker.addOnPositiveButtonClickListener(v -> {
            calendar.set(Calendar.HOUR_OF_DAY, picker.getHour());
            calendar.set(Calendar.MINUTE, picker.getMinute());
            targetView.setText(dateFormat.format(calendar.getTime()));
            if (onTimeSet != null) onTimeSet.run();
        });

        picker.show(getSupportFragmentManager(), "MATERIAL_TIME_PICKER");
    }

    private void submitShift(long entryTime, long leaveTime, String place, String notes1, String notes2) {
        long now = System.currentTimeMillis();
        if (leaveTime <= entryTime || leaveTime > now + 60000 || entryTime > now + 60000) {
            Toast.makeText(this, "Invalid shift: check your times (cannot be in the future)", Toast.LENGTH_LONG).show();
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
            payload.put("notes1", notes1);
            payload.put("notes2", notes2);
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
                        showCelebration();
                    } else if (state == WorkInfo.State.FAILED) {
                        Toast.makeText(this, "Network error: Shift queued for retry.", Toast.LENGTH_LONG).show();
                    }
                }
            }
        });

        Toast.makeText(this, "Processing shift submission...", Toast.LENGTH_SHORT).show();
    }

    private void showCelebration() {
        ImageView ivCelebration = findViewById(R.id.ivCelebration);
        if (ivCelebration == null) return;

        ivCelebration.setVisibility(View.VISIBLE);
        ivCelebration.setAlpha(0f);
        ivCelebration.setScaleX(0.5f);
        ivCelebration.setScaleY(0.5f);

        ivCelebration.animate()
                .alpha(1f)
                .scaleX(1.2f)
                .scaleY(1.2f)
                .setDuration(500)
                .withEndAction(() -> {
                    ivCelebration.animate()
                            .alpha(0f)
                            .scaleX(1.5f)
                            .scaleY(1.5f)
                            .setDuration(500)
                            .setStartDelay(1000)
                            .withEndAction(() -> ivCelebration.setVisibility(View.GONE))
                            .start();
                })
                .start();
    }
}
