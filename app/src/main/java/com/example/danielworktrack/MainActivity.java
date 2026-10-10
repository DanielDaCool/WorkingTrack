package com.example.danielworktrack;

import android.app.TimePickerDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.InsetDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
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
    private boolean discardConfirmShowing = false;
    private NetworkManager networkManager;
    private TextView tvStatus;
    private Button btnAction;
    private TextView tvTimer;
    private TextView tvEnteredAt;
    private View viewStatusDot;
    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            refreshTimer();
            timerHandler.postDelayed(this, 30000);
        }
    };
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.US);
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
    private final SimpleDateFormat sheetDateFormat = new SimpleDateFormat("d.M.yy", Locale.getDefault());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        setContentView(R.layout.activity_main);

        storageManager = new StorageManager(this);
        networkManager = new NetworkManager();

        tvStatus = findViewById(R.id.tvStatus);
        btnAction = findViewById(R.id.btnAction);
        tvTimer = findViewById(R.id.tvTimer);
        tvEnteredAt = findViewById(R.id.tvEnteredAt);
        viewStatusDot = findViewById(R.id.viewStatusDot);

        // Edge-to-edge: keep content clear of the system bars.
        View root = findViewById(R.id.rootMain);
        final int padL = root.getPaddingLeft(), padT = root.getPaddingTop(),
                padR = root.getPaddingRight(), padB = root.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(padL + bars.left, padT + bars.top, padR + bars.right, padB + bars.bottom);
            return insets;
        });

        TextView tvDate = findViewById(R.id.tvDate);
        tvDate.setText(new SimpleDateFormat("EEEE, d בMMMM", new Locale("iw")).format(new java.util.Date()));
        TextView tvGreeting = findViewById(R.id.tvGreeting);
        tvGreeting.setText(getGreeting() + ", דניאל");

        updateUIState();
        fetchPlacesDynamic();

        btnAction.setOnClickListener(v -> {
            if (storageManager.getClockInTime() == -1) {
                storageManager.saveClockInTime(System.currentTimeMillis());
                updateUIState();
                Toast.makeText(this, "נכנסת לשיעור", Toast.LENGTH_SHORT).show();
            } else {
                showCheckoutDialog();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        startTimer();
    }

    @Override
    protected void onPause() {
        super.onPause();
        timerHandler.removeCallbacks(timerRunnable);
    }

    @Override
    protected void onDestroy() {
        timerHandler.removeCallbacks(timerRunnable);
        super.onDestroy();
    }

    private void startTimer() {
        timerHandler.removeCallbacks(timerRunnable);
        refreshTimer();
        if (storageManager.getClockInTime() != -1) {
            timerHandler.postDelayed(timerRunnable, 30000);
        }
    }

    private void refreshTimer() {
        long clockInTime = storageManager.getClockInTime();
        if (clockInTime == -1) {
            tvTimer.setText("--:--");
            return;
        }
        long minutesTotal = Math.max(0, (System.currentTimeMillis() - clockInTime) / 60000);
        tvTimer.setText(String.format(Locale.US, "%02d:%02d", minutesTotal / 60, minutesTotal % 60));
    }

    private void updateUIState() {
        long clockInTime = storageManager.getClockInTime();
        int teal = ContextCompat.getColor(this, R.color.md_theme_primary);
        int onTeal = ContextCompat.getColor(this, R.color.md_theme_onPrimary);
        int danger = ContextCompat.getColor(this, R.color.md_theme_error);
        int onDanger = ContextCompat.getColor(this, R.color.md_theme_onError);
        int secondary = ContextCompat.getColor(this, R.color.text_secondary);

        if (clockInTime == -1) {
            tvStatus.setText("לא בשיעור");
            tvStatus.setTextColor(secondary);
            viewStatusDot.setBackgroundTintList(ColorStateList.valueOf(secondary));
            tvEnteredAt.setVisibility(View.INVISIBLE);
        } else {
            tvStatus.setText("בשיעור");
            tvStatus.setTextColor(teal);
            viewStatusDot.setBackgroundTintList(ColorStateList.valueOf(teal));
            tvEnteredAt.setText("נכנסת ב-" + timeFormat.format(clockInTime));
            tvEnteredAt.setVisibility(View.VISIBLE);
        }
        startTimer();

        // Animated state transition
        btnAction.animate().scaleX(0.95f).scaleY(0.95f).setDuration(100).withEndAction(() -> {
            com.google.android.material.button.MaterialButton button =
                    (com.google.android.material.button.MaterialButton) btnAction;
            boolean out = clockInTime == -1;
            button.setText(out ? "כניסה לשיעור" : "יציאה מהשיעור");
            button.setBackgroundTintList(ColorStateList.valueOf(out ? teal : danger));
            button.setTextColor(out ? onTeal : onDanger);
            button.setIconTint(ColorStateList.valueOf(out ? onTeal : onDanger));
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
        // Swiping the sheet down (or Back) cancels the dialog; route that through a
        // confirmation instead of closing, so the shift is only discarded on "yes".
        BottomSheetDialog bottomSheet = new BottomSheetDialog(this) {
            @Override
            public void cancel() {
                // A fast double Back can call cancel() twice; show only one popup.
                if (discardConfirmShowing) return;
                discardConfirmShowing = true;
                confirmDiscardShift(this);
            }
        };
        bottomSheet.setContentView(R.layout.layout_bottom_sheet_confirm);
        bottomSheet.setCancelable(true);
        bottomSheet.setCanceledOnTouchOutside(false);
        bottomSheet.getBehavior().setSkipCollapsed(true);

        // Ensure the dialog resizes when the keyboard appears
        if (bottomSheet.getWindow() != null) {
            bottomSheet.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }

        // Force the bottom sheet to start expanded
        View bottomSheetInternal = bottomSheet.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (bottomSheetInternal != null) {
            BottomSheetBehavior.from(bottomSheetInternal).setState(BottomSheetBehavior.STATE_EXPANDED);
        }

        TextView tvEntry = bottomSheet.findViewById(R.id.tvEntryTime);
        TextView tvLeave = bottomSheet.findViewById(R.id.tvLeaveTime);
        View cardEntry = bottomSheet.findViewById(R.id.cardEntry);
        View cardLeave = bottomSheet.findViewById(R.id.cardLeave);
        AutoCompleteTextView actvPlaces = bottomSheet.findViewById(R.id.actvPlaces);
        AutoCompleteTextView actvMeetingType = bottomSheet.findViewById(R.id.actvMeetingType);
        AutoCompleteTextView actvDediLed = bottomSheet.findViewById(R.id.actvDediLed);
        com.google.android.material.textfield.TextInputLayout tilStudentCount = bottomSheet.findViewById(R.id.tilStudentCount);
        TextInputEditText etStudentCount = bottomSheet.findViewById(R.id.etStudentCount);
        TextInputEditText etNotes1 = bottomSheet.findViewById(R.id.etNotes1);
        TextInputEditText etNotes2 = bottomSheet.findViewById(R.id.etNotes2);
        Button btnSubmit = bottomSheet.findViewById(R.id.btnSubmit);
        TextView tvDuration = bottomSheet.findViewById(R.id.tvDurationPreview);
        TextView tvError = bottomSheet.findViewById(R.id.tvTimeError);

        Calendar entryCal = Calendar.getInstance();
        entryCal.setTimeInMillis(storageManager.getClockInTime());

        Calendar leaveCal = Calendar.getInstance();
        leaveCal.setTimeInMillis(System.currentTimeMillis());

        if (tvEntry != null && tvLeave != null && cardEntry != null && cardLeave != null && 
            actvPlaces != null && actvMeetingType != null && 
            actvDediLed != null && etStudentCount != null && etNotes1 != null && 
            etNotes2 != null && btnSubmit != null && tvDuration != null && 
            tvError != null) {
            tvEntry.setText(timeFormat.format(entryCal.getTime()));
            tvLeave.setText(timeFormat.format(leaveCal.getTime()));

            // Initial validation check
            updateValidationState(entryCal, leaveCal, tvDuration, tvError, btnSubmit);

            cardEntry.setOnClickListener(v ->
                    showTimePicker(entryCal, tvEntry, () ->
                        updateValidationState(entryCal, leaveCal, tvDuration, tvError, btnSubmit)));

            cardLeave.setOnClickListener(v ->
                    showTimePicker(leaveCal, tvLeave, () ->
                        updateValidationState(entryCal, leaveCal, tvDuration, tvError, btnSubmit)));

            // Load whatever is currently cached first
            List<String> places = storageManager.getPlaces();
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.item_spinner_rtl, places);
            adapter.setDropDownViewResource(R.layout.item_spinner_rtl);
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
                                boolean frc = isFrcShift(actvPlaces.getText().toString(), actvMeetingType.getText().toString());
                                if (tilStudentCount != null) tilStudentCount.setVisibility(frc ? View.GONE : View.VISIBLE);
                            }
                        });
                    }
                }
            });

            // Initialize Meeting Type Dropdown
            String[] meetingTypes = {"שיעור", "תגבור", "תחרות", "אסיפת הורים", "frc", "אחר"};
            ArrayAdapter<String> meetingAdapter = new ArrayAdapter<>(this, R.layout.item_spinner_rtl, meetingTypes);
            actvMeetingType.setAdapter(meetingAdapter);
            // Default to "שיעור"
            actvMeetingType.setText("שיעור", false);

            // Initialize Dedi Led Dropdown
            String[] dediLedOptions = {"הוביל את השיעור", "נכח בשיעור", "לא היה", "אירים באה לתגבר", "אירים החליפה אותי", "מני החליף אותי"};
            ArrayAdapter<String> dediLedAdapter = new ArrayAdapter<>(this, R.layout.item_spinner_rtl, dediLedOptions);
            actvDediLed.setAdapter(dediLedAdapter);
            // Default to "לא היה"
            actvDediLed.setText("לא היה", false);

            // FRC lessons don't record attendance: hide the field and don't require it.
            Runnable updateStudentField = () -> {
                boolean frc = isFrcShift(actvPlaces.getText().toString(), actvMeetingType.getText().toString());
                if (tilStudentCount != null) {
                    tilStudentCount.setVisibility(frc ? View.GONE : View.VISIBLE);
                    if (frc) tilStudentCount.setError(null);
                }
            };
            actvPlaces.setOnItemClickListener((parent, view, position, id) -> updateStudentField.run());
            actvMeetingType.setOnItemClickListener((parent, view, position, id) -> updateStudentField.run());
            updateStudentField.run();

            btnSubmit.setOnClickListener(v -> {
                String selectedPlace = actvPlaces.getText().toString();
                if (selectedPlace.isEmpty()) selectedPlace = "Default Office";
                
                String meetingType = actvMeetingType.getText().toString();
                String dediLed = actvDediLed.getText().toString();
                String studentCount = etStudentCount.getText() != null ? etStudentCount.getText().toString() : "";
                boolean frc = isFrcShift(selectedPlace, meetingType);
                if (frc) studentCount = "";

                if (!frc && studentCount.trim().isEmpty()) {
                    if (tilStudentCount != null) {
                        tilStudentCount.setError("חובה להזין מספר ילדים");
                    }
                    Toast.makeText(this, "חובה להזין מספר ילדים לפני השליחה", Toast.LENGTH_SHORT).show();
                    return;
                }

                else {
                    if (tilStudentCount != null) {
                        tilStudentCount.setError(null);
                    }
                }
                
                String notes1 = etNotes1.getText() != null ? etNotes1.getText().toString() : "";
                String notes2 = etNotes2.getText() != null ? etNotes2.getText().toString() : "";
                submitShift(entryCal.getTimeInMillis(), leaveCal.getTimeInMillis(), selectedPlace, notes1, notes2, meetingType, dediLed, studentCount);
                bottomSheet.dismiss();
                storageManager.clearActiveShift();
                updateUIState();
            });
        }

        bottomSheet.show();
    }

    /** Same rule as isFrcShift in apps-script/appBackend.gs: "frc" in the place or the meeting type. */
    private static boolean isFrcShift(String place, String meetingType) {
        String p = place == null ? "" : place.toLowerCase(Locale.ROOT);
        String m = meetingType == null ? "" : meetingType.toLowerCase(Locale.ROOT);
        return p.contains("frc") || m.contains("frc");
    }

    private void confirmDiscardShift(BottomSheetDialog bottomSheet) {
        View content = getLayoutInflater().inflate(R.layout.dialog_cancel_confirm, null);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(content)
                .setCancelable(false)
                .create();
        if (dialog.getWindow() != null) {
            int inset = Math.round(24 * getResources().getDisplayMetrics().density);
            dialog.getWindow().setBackgroundDrawable(new InsetDrawable(new ColorDrawable(Color.TRANSPARENT), inset));
        }
        content.findViewById(R.id.btnDiscardYes).setOnClickListener(v -> {
            discardConfirmShowing = false;
            storageManager.clearActiveShift();
            dialog.dismiss();
            bottomSheet.dismiss();
            updateUIState();
            Toast.makeText(this, "השיעור בוטל", Toast.LENGTH_SHORT).show();
        });
        content.findViewById(R.id.btnDiscardNo).setOnClickListener(v -> {
            discardConfirmShowing = false;
            dialog.dismiss();
            bottomSheet.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        });
        dialog.show();
    }

    private void updateValidationState(Calendar entry, Calendar leave, TextView tvDuration, TextView tvError, Button btnSubmit) {
        long durationMs = leave.getTimeInMillis() - entry.getTimeInMillis();
        long now = System.currentTimeMillis();

        boolean isFuture = entry.getTimeInMillis() > now || leave.getTimeInMillis() > now;
        boolean isNegative = durationMs <= 0;

        if (isNegative || isFuture) {
            tvDuration.setText("0.00 ש׳");
            tvError.setText(isNegative ? "שעת היציאה חייבת להיות אחרי שעת הכניסה" : "אי אפשר לבחור שעה עתידית");
            tvError.setVisibility(View.VISIBLE);
            btnSubmit.setEnabled(false);
            btnSubmit.setAlpha(0.5f);
        } else {
            double durationHours = durationMs / (1000.0 * 60.0 * 60.0);
            tvDuration.setText(String.format(Locale.US, "%.2f ש׳", durationHours));
            tvError.setVisibility(View.GONE);
            btnSubmit.setEnabled(true);
            btnSubmit.setAlpha(1.0f);
        }
    }

    private String getGreeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (hour >= 5 && hour < 12) return "בוקר טוב";
        if (hour >= 12 && hour < 17) return "צהריים טובים";
        if (hour >= 17 && hour < 21) return "ערב טוב";
        return "לילה טוב";
    }

    private void showTimePicker(Calendar calendar, TextView targetView, Runnable onTimeSet) {
        MaterialTimePicker picker = new MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_24H)
                .setHour(calendar.get(Calendar.HOUR_OF_DAY))
                .setMinute(calendar.get(Calendar.MINUTE))
                .setTitleText("בחר שעה")
                .setPositiveButtonText("אישור")
                .setNegativeButtonText("ביטול")
                .build();

        picker.addOnPositiveButtonClickListener(v -> {
            calendar.set(Calendar.HOUR_OF_DAY, picker.getHour());
            calendar.set(Calendar.MINUTE, picker.getMinute());
            targetView.setText(timeFormat.format(calendar.getTime()));
            if (onTimeSet != null) onTimeSet.run();
        });

        picker.show(getSupportFragmentManager(), "MATERIAL_TIME_PICKER");
    }

    private void submitShift(long entryTime, long leaveTime, String place, String notes1, String notes2, String meetingType, String dediLed, String studentCount) {

        long now = System.currentTimeMillis();
        if (leaveTime <= entryTime || leaveTime > now + 60000 || entryTime > now + 60000) {
            Toast.makeText(this, "השיעור לא תקין: בדוק את השעות (אי אפשר לבחור שעה עתידית)", Toast.LENGTH_LONG).show();
            return;
        }
        entryTime = Math.round(entryTime / 300000.0) * 300000;
        leaveTime = Math.round(leaveTime / 300000.0) * 300000;

        double durationMs = leaveTime - entryTime;
        double durationHours = durationMs / (1000.0 * 60.0 * 60.0);
        String formattedDuration = String.format(Locale.US, "%.2f", durationHours);

        JSONObject payload = new JSONObject();
        try {
            payload.put("date", sheetDateFormat.format(entryTime));
            payload.put("entryTime", dateFormat.format(entryTime).split(" ")[1]);
            payload.put("leaveTime", dateFormat.format(leaveTime).split(" ")[1]);
            payload.put("place", place);
            payload.put("duration", formattedDuration);
            payload.put("notes1", notes1);
            payload.put("notes2", notes2);
            payload.put("meetingType", meetingType);
            payload.put("dediLed", dediLed);
            payload.put("studentCount", studentCount);
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
                        Toast.makeText(this, "השיעור נשמר בגיליון", Toast.LENGTH_LONG).show();
                        showCelebration();
                    } else if (state == WorkInfo.State.FAILED) {
                        Toast.makeText(this, "שגיאת רשת: השיעור ממתין לניסיון חוזר", Toast.LENGTH_LONG).show();
                    }
                }
            }
        });

        Toast.makeText(this, "שולח את השיעור...", Toast.LENGTH_SHORT).show();
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
