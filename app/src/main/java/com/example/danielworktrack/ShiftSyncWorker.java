package com.example.danielworktrack;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/**
 * Background worker responsible for reliably synchronizing queued shifts to Google Sheets.
 */
public class ShiftSyncWorker extends Worker {

    private final NetworkManager networkManager;

    public ShiftSyncWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
        networkManager = new NetworkManager();
    }

    @NonNull
    @Override
    public Result doWork() {
        String payload = getInputData().getString("payload");
        if (payload == null) {
            return Result.failure();
        }

        boolean success = networkManager.syncShiftSync(payload);
        if (success) {
            return Result.success();
        } else {
            // Automatically retries if network fails temporarily
            return Result.retry();
        }
    }
}