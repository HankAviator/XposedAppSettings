package ru.bluecat.android.xposed.mods.appsettings.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

import ru.bluecat.android.xposed.mods.appsettings.Common;
import ru.bluecat.android.xposed.mods.appsettings.R;
import ru.bluecat.android.xposed.mods.appsettings.SELinux;

public class BackupActivity extends AppCompatActivity {

    static boolean restoreSuccessful;
    static boolean backupSuccessful;
    private static SharedPreferences prefs;

    static void startBackupActivity(MainActivity activity, boolean isRestore) {
        Intent i = new Intent(activity, BackupActivity.class);
        i.putExtra("backup", isRestore);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        showFilePicker(getIntent().getBooleanExtra("backup", false));
    }

    private void showFilePicker(boolean isRestore) {
        try {
            String action = Intent.ACTION_CREATE_DOCUMENT;
            int requestCode = 1;
            if(isRestore) {
                action = Intent.ACTION_OPEN_DOCUMENT;
                requestCode = 2;
            }

            Intent intent = new Intent(action);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            if(!isRestore) {
                intent.putExtra(Intent.EXTRA_TITLE, createUniqueBackupName());
            }
            startActivityForResult(intent, requestCode);

        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.imp_exp_file_picker_error, Toast.LENGTH_LONG).show();
            Log.e(Common.TAG, e.toString());
            e.printStackTrace();
            finish();
        }
    }

    @Override
    public void onActivityResult(final int requestCode, final int resultCode, final Intent resultData) {
        super.onActivityResult(requestCode, resultCode, resultData);
        if (requestCode == 1 && resultCode == Activity.RESULT_OK) {
            Uri treeUri = resultData.getData();
            if (treeUri != null) {
                new BackupTask(this).execute(treeUri);
            }
        }
        if (requestCode == 2 && resultCode == Activity.RESULT_OK) {
            Uri documentUri = resultData.getData();
            if (documentUri != null) {
                new RestoreTask(this).execute(documentUri);
            }
        }
        finish();
    }

    private static String createUniqueBackupName() {
        SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US);
        Date formatedDate = new Date(System.currentTimeMillis());
        return "AppSettings_" + formatter.format(formatedDate) + ".backup";
    }

    private static void getLegacyPrefs (Activity context) {
        Context ctx = ContextCompat.createDeviceProtectedStorageContext(context);
        if (ctx == null) {
            ctx = context;
        }
        prefs = ctx.getSharedPreferences(Common.PREFS, Context.MODE_PRIVATE);
    }

    private static class BackupTask extends AsyncTask<Uri, String, String> {

        private final WeakReference<BackupActivity> activityReference;

        BackupTask(BackupActivity context) {
            activityReference = new WeakReference<>(context);
        }

        @SuppressLint("WorldReadableFiles")
        @Override
        protected String doInBackground(Uri... params) {
            backupSuccessful = false;
            BackupActivity activity = activityReference.get();

            ObjectOutputStream output = null;
            String error = null;
            try {
                try {
                    //noinspection deprecation
                    prefs = activity.getSharedPreferences(Common.PREFS, Context.MODE_WORLD_READABLE);
                } catch (SecurityException e) {
                    if(SELinux.isSELinuxPermissive()) {
                        getLegacyPrefs(activity);
                    } else {
                        MainActivity.frameworkWarning(activity, 2);
                    }
                }
                if(prefs != null) {
                    output = new ObjectOutputStream(activity.getContentResolver().openOutputStream(params[0]));
                    output.writeObject(prefs.getAll());
                    backupSuccessful = true;
                }
            } catch (IOException e) {
                error = e.getMessage();
                e.printStackTrace();
            } finally {
                try {
                    if (output != null) {
                        output.flush();
                        output.close();
                    }
                } catch (IOException e) {
                    error = e.getMessage();
                    e.printStackTrace();
                }
            }

            if(!backupSuccessful) {
                return activity.getResources().getString(R.string.imp_exp_backup_error, error);
            } else return null;
        }

        @Override
        protected void onPostExecute(String result) {
            if(!backupSuccessful) {
                Toast.makeText(activityReference.get(), result, Toast.LENGTH_LONG).show();
            } else {
                backupSuccessful = false;
                MainActivity.showBackupSnackbar(R.string.imp_exp_backup_completed);
            }
        }
    }

    private static class RestoreTask extends AsyncTask<Uri, String, String> {
        private final WeakReference<BackupActivity> activityReference;

        RestoreTask(BackupActivity context) {
            activityReference = new WeakReference<>(context);
        }

        @SuppressLint("WorldReadableFiles")
        @Override
        protected String doInBackground(Uri... params) {
            restoreSuccessful = false;
            BackupActivity activity = activityReference.get();

            ObjectInputStream input = null;
            String error = null;
            try {
                try {
                    //noinspection deprecation
                    prefs = activity.getSharedPreferences(Common.PREFS, Context.MODE_WORLD_READABLE);
                } catch (SecurityException ignored) {
                    if(SELinux.isSELinuxPermissive()) {
                        getLegacyPrefs(activity);
                    } else {
                        MainActivity.frameworkWarning(activity, 2);
                    }
                }
                if(prefs != null) {
                    input = new ObjectInputStream(activity.getContentResolver().openInputStream(params[0]));
                    SharedPreferences.Editor prefEdit = prefs.edit();
                    prefEdit.clear();

                    @SuppressWarnings("unchecked")
                    Map<String, ?> entries = (Map<String, ?>) input.readObject();
                    for (Map.Entry<String, ?> entry : entries.entrySet()) {
                        Object v = entry.getValue();
                        String key = entry.getKey();

                        if (v instanceof Boolean)
                            prefEdit.putBoolean(key, (Boolean) v);
                        else if (v instanceof Float)
                            prefEdit.putFloat(key, (Float) v);
                        else if (v instanceof Integer)
                            prefEdit.putInt(key, (Integer) v);
                        else if (v instanceof Long)
                            prefEdit.putLong(key, (Long) v);
                        else if (v instanceof String)
                            prefEdit.putString(key, ((String) v));
                    }
                    prefEdit.apply();
                    restoreSuccessful = true;
                }
            } catch (IOException | ClassNotFoundException e) {
                error = e.getMessage();
                e.printStackTrace();
            } finally {
                try {
                    if (input != null) {
                        input.close();
                    }
                } catch (IOException e) {
                    error = e.getMessage();
                    e.printStackTrace();
                }
            }

            if(!restoreSuccessful) {
                return activity.getResources().getString(R.string.imp_exp_restore_error, error);
            } else return null;
        }

        @Override
        protected void onPostExecute(String result) {
            if(!restoreSuccessful) {
                Toast.makeText(activityReference.get(), result, Toast.LENGTH_LONG).show();
            } else {
                MainActivity.refreshAppsAfterImport();
            }
        }
    }
}
