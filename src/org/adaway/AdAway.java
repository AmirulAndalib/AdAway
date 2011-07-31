/*
 * Copyright (C) 2011 Dominik Schürmann <dominik@dominikschuermann.de>
 *
 * This file is part of AdAway.
 * 
 * AdAway is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AdAway is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AdAway.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package org.adaway;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;

import org.adaway.utils.DatabaseHelper;
import org.adaway.utils.HostsParser;
import org.adaway.utils.SharedPrefs;
import org.adaway.utils.Constants;
import android.app.Activity;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface.OnCancelListener;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.StatFs;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.TextView;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Configuration;

import com.stericson.RootTools.*;

public class AdAway extends Activity {
    private Context mContext;
    private DatabaseHelper mDatabaseHelper;

    private ProgressDialog mApplyProgressDialog;

    /**
     * Don't recreate activity on orientation change, it will break AsyncTask. Using possibility 4
     * from http://blog.doityourselfandroid
     * .com/2010/11/14/handling-progress-dialogs-and-screen-orientation-changes/
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        setContentView(R.layout.main);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.main, menu);
        return true;
    }

    /**
     * Menu Options
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle item selection
        switch (item.getItemId()) {
        case R.id.menu_hosts_sources:
            startActivity(new Intent(this, HostsSources.class));
            return true;

        case R.id.menu_blacklist:
            startActivity(new Intent(this, Blacklist.class));
            return true;

        case R.id.menu_whitelist:
            startActivity(new Intent(this, Whitelist.class));
            return true;

        case R.id.menu_redirection_list:
            startActivity(new Intent(this, RedirectionList.class));
            return true;

        case R.id.menu_preferences:
            startActivity(new Intent(this, Preferences.class));
            return true;

        case R.id.menu_about:
            showAboutDialog();
            return true;

        default:
            return super.onOptionsItemSelected(item);
        }
    }

    /**
     * Called when the activity is first created.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        mContext = this;

        RootTools.debugMode = false;

        // check for root on device
        if (!RootTools.isRootAvailable()) {
            // su binary does not exist, raise no root dialog
            showNoRootDialog();
        } else {
            // su binary exists, request permission
            if (!RootTools.isAccessGiven()) {
                showNoRootDialog();
            } else {
                if (!RootTools.isBusyboxAvailable()) { // checking for busybox needs root
                    showNoRootDialog();
                }
            }
        }
    }

    /**
     * Button Action to download and apply hosts files
     * 
     * @param view
     */
    public void applyOnClick(View view) {
        mDatabaseHelper = new DatabaseHelper(mContext);

        // get enabled hosts from databse
        ArrayList<String> enabledHosts = mDatabaseHelper.getAllEnabledHostsSources();
        Log.d(Constants.TAG, "Enabled hosts: " + enabledHosts.toString());

        mDatabaseHelper.close();

        // build array out of list
        String[] enabledHostsArray = new String[enabledHosts.size()];
        enabledHosts.toArray(enabledHostsArray);

        if (enabledHosts.size() < 1) {
            AlertDialog alertDialog = new AlertDialog.Builder(mContext).create();
            alertDialog.setIcon(android.R.drawable.ic_dialog_alert);
            alertDialog.setTitle(R.string.no_sources_title);
            alertDialog.setMessage(getString(org.adaway.R.string.no_sources));
            alertDialog.setButton(getString(R.string.button_close),
                    new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dlg, int sum) {
                            dlg.dismiss();
                        }
                    });
            alertDialog.show();
        } else {
            // execute downloading of files
            download(enabledHostsArray);
        }
    }

    /**
     * Button Action to Revert to default hosts file
     * 
     * @param view
     */
    public void revertOnClick(View view) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.button_revert);
        builder.setMessage(getString(R.string.revert_question));
        builder.setIcon(android.R.drawable.ic_dialog_alert);
        builder.setCancelable(false);
        builder.setPositiveButton(getString(R.string.button_yes),
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        // build standard hosts file
                        try {
                            FileOutputStream fos = openFileOutput(Constants.HOSTS_FILENAME,
                                    Context.MODE_PRIVATE);

                            // default localhost
                            String localhost = Constants.LOCALHOST_IPv4 + " "
                                    + Constants.LOCALHOST_HOSTNAME;
                            fos.write(localhost.getBytes());
                            fos.close();

                            // copy hosts file with RootTools
                            if (!copyHostsFile()) {
                                Log.e(Constants.TAG, "revert: problem with copying hosts file");
                                throw new Exception();
                            }

                            // delete generated hosts file after applying it
                            deleteFile(Constants.HOSTS_FILENAME);

                            AlertDialog alertDialog = new AlertDialog.Builder(mContext).create();
                            alertDialog.setIcon(android.R.drawable.ic_dialog_info);
                            alertDialog.setTitle(R.string.button_revert);
                            alertDialog
                                    .setMessage(getString(org.adaway.R.string.revert_successfull));
                            alertDialog.setButton(getString(R.string.button_close),
                                    new DialogInterface.OnClickListener() {
                                        public void onClick(DialogInterface dlg, int sum) {
                                            // do nothing, close
                                        }
                                    });
                            alertDialog.show();

                        } catch (Exception e) {
                            Log.e(Constants.TAG, "Exception: " + e);
                            e.printStackTrace();

                            AlertDialog alertDialog = new AlertDialog.Builder(mContext).create();
                            alertDialog.setIcon(android.R.drawable.ic_dialog_alert);
                            alertDialog.setTitle(R.string.button_revert);
                            alertDialog.setMessage(getString(org.adaway.R.string.revert_problem));
                            alertDialog.setButton(getString(R.string.button_close),
                                    new DialogInterface.OnClickListener() {
                                        public void onClick(DialogInterface dlg, int sum) {
                                            dlg.dismiss();
                                        }
                                    });
                            alertDialog.show();
                        }

                    }
                });
        builder.setNegativeButton(getString(R.string.button_no),
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        dialog.cancel();
                    }
                });
        AlertDialog question = builder.create();
        question.show();
    }

    /**
     * About Dialog of AdAway
     */
    private void showAboutDialog() {
        final Dialog dialog = new Dialog(mContext);
        dialog.requestWindowFeature(Window.FEATURE_LEFT_ICON);
        dialog.setContentView(R.layout.about_dialog);
        dialog.setTitle(R.string.about_title);

        TextView versionText = (TextView) dialog.findViewById(R.id.about_version);
        versionText.setText(getString(R.string.about_version) + " " + getVersion());

        Button closeBtn = (Button) dialog.findViewById(R.id.about_close);
        closeBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                dialog.cancel();
            }
        });

        dialog.show();
        dialog.setFeatureDrawableResource(Window.FEATURE_LEFT_ICON,
                android.R.drawable.ic_dialog_info);
    }

    /**
     * Get the current package version.
     * 
     * @return The current version.
     */
    private String getVersion() {
        String result = "";
        try {
            PackageManager manager = this.getPackageManager();
            PackageInfo info = manager.getPackageInfo(this.getPackageName(), 0);

            result = String.format("%s (%s)", info.versionName, info.versionCode);
        } catch (NameNotFoundException e) {
            Log.w(Constants.TAG, "Unable to get application version: " + e.getMessage());
            result = "Unable to get application version.";
        }

        return result;
    }

    /**
     * Dialog raised when Android is not rooted, showing some information.
     */
    private void showNoRootDialog() {
        final Dialog dialog = new Dialog(mContext);
        dialog.requestWindowFeature(Window.FEATURE_LEFT_ICON);
        dialog.setContentView(R.layout.no_root_dialog);
        dialog.setTitle(R.string.no_root_title);

        // Exit Button closes application
        Button exitButton = (Button) dialog.findViewById(R.id.no_root_exit);
        exitButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                finish(); // finish current activity, means exiting app
            }
        });

        // when dialog is closed by pressing back exit app
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialogInterface) {
                finish();
            }
        });

        dialog.show();
        dialog.setFeatureDrawableResource(Window.FEATURE_LEFT_ICON,
                android.R.drawable.ic_dialog_alert);
    }

    /**
     * Check if there is enough space on internal partition
     * 
     * @param size
     *            size of file to put on partition
     * @param path
     *            path where to put the file
     * 
     * @return <code>true</code> if it will fit on partition of <code>path</code>,
     *         <code>false</code> if it will not fit.
     */
    public static boolean hasEnoughSpaceOnPartition(String path, long size) {
        StatFs stat = new StatFs(path);
        long blockSize = stat.getBlockSize();
        long availableBlocks = stat.getAvailableBlocks();

        if (size < availableBlocks * blockSize) {
            return true;
        } else {
            Log.e(Constants.TAG, "Not enough space on partition!");
            return false;
        }
    }

    /**
     * Copy hosts file from private storage of AdAway to internal partition using RootTools library
     * 
     * @return <code>true</code> if copying was successful, <code>false</code> if there were some
     *         problems like not enough space.
     */
    private boolean copyHostsFile() {
        String privateDir = getFilesDir().getAbsolutePath();
        String privateFile = privateDir + File.separator + Constants.HOSTS_FILENAME;
        String hostsFile = Constants.ANDROID_HOSTS_PATH + File.separator + Constants.HOSTS_FILENAME;

        String commandCopy = Constants.COMMAND_COPY + " " + privateFile + " " + hostsFile;
        String commandChown = Constants.COMMAND_CHOWN + " " + hostsFile;
        String commandChmod = Constants.COMMAND_CHMOD + " " + hostsFile;
        Log.d(Constants.TAG, "commandCopy: " + commandCopy);
        Log.d(Constants.TAG, "commandChown: " + commandChown);
        Log.d(Constants.TAG, "commandChmod: " + commandChmod);

        // do it with RootTools
        try {
            // check for space on partition
            long size = new File(privateFile).length();
            Log.d(Constants.TAG, "size: " + size);
            if (!hasEnoughSpaceOnPartition(Constants.ANDROID_HOSTS_PATH, size)) {
                throw new Exception();
            }

            // remount for write access
            boolean mountSuccess = RootTools.remount(Constants.ANDROID_HOSTS_PATH, "RW");

            List<String> output;
            // copy
            output = RootTools.sendShell(commandCopy);
            Log.d(Constants.TAG, "output of command: " + output.toString());

            // chown
            output = RootTools.sendShell(commandChown);
            Log.d(Constants.TAG, "output of command: " + output.toString());

            // chmod
            output = RootTools.sendShell(commandChmod);
            Log.d(Constants.TAG, "output of command: " + output.toString());

        } catch (Exception e) {
            Log.e(Constants.TAG, "Exception: " + e);
            e.printStackTrace();

            return false;
        } finally {
            // after all remount back as read only
            RootTools.remount(Constants.ANDROID_HOSTS_PATH, "RO");
        }

        return true;
    }

    /**
     * Async Thread to download hosts files, can be executed with many urls as params. In
     * onPostExecute an Apply Async Thread will be started
     * 
     */
    private void download(String... urls) {
        AsyncTask<String, Integer, Integer> downloadHostsSources = new AsyncTask<String, Integer, Integer>() {

            private volatile boolean running = true;
            private ProgressDialog mDownloadProgressDialog;

            private String currentURL;
            private int fileSize;
            private byte data[];
            private long total;
            private int count;
            private boolean urlChanged;

            private int RETURN_SUCCESS = 1;
            private int RETURN_NO_CONNECTION = 2;
            private int RETURN_DOWNLOAD_FAIL = 3;
            private int RETURN_PRIVATE_FILE = 4;

            @Override
            protected void onCancelled() {
                Log.d(Constants.TAG, "AsyncTask canceled!");
                running = false;
            }

            @Override
            protected void onPreExecute() {
                super.onPreExecute();
                mDownloadProgressDialog = new ProgressDialog(mContext);
                mDownloadProgressDialog.setMessage(getString(R.string.download_dialog));
                mDownloadProgressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
                mDownloadProgressDialog.setCancelable(true);
                mDownloadProgressDialog.setOnCancelListener(new OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface dialog) {
                        // actually could set running = false; right here, but I'll
                        // stick to contract.
                        cancel(true);
                    }
                });

                mDownloadProgressDialog.show();

                urlChanged = false;
            }

            private boolean isAndroidOnline() {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                NetworkInfo netInfo = cm.getActiveNetworkInfo();
                if (netInfo != null && netInfo.isConnectedOrConnecting()) {
                    return true;
                }
                return false;
            }

            @Override
            protected Integer doInBackground(String... urls) {
                int returnCode = RETURN_SUCCESS; // default return code

                if (isAndroidOnline()) {
                    // output to write into
                    FileOutputStream out = null;

                    try {
                        out = openFileOutput(Constants.DOWNLOADED_HOSTS_FILENAME,
                                Context.MODE_PRIVATE);

                        for (String url : urls) {

                            // stop if thread canceled
                            if (!running) {
                                break;
                            }

                            InputStream is = null;
                            BufferedInputStream bis = null;
                            try {
                                Log.v(Constants.TAG, "Downloading hosts file: " + url);

                                /* change URL in download dialog */
                                currentURL = url;
                                urlChanged = true;
                                publishProgress(0);

                                /* build connection */
                                URL mURL = new URL(url);
                                // if (mURL.getProtocol() == "http") { // TODO: implement SSL
                                // httpsURLConnection
                                URLConnection connection = mURL.openConnection();
                                // } else if (mURL.getProtocol() == "https") {
                                //
                                // } else {
                                // Log.e(TAG, "wrong protocol");
                                // }
                                fileSize = connection.getContentLength();
                                Log.d(Constants.TAG, "fileSize: " + fileSize);

                                // TODO:
                                // long getLastModified()
                                // Returns the value of the last-modified header field.

                                connection.connect();

                                is = connection.getInputStream();
                                bis = new BufferedInputStream(is);

                                if (is == null) {
                                    Log.e(Constants.TAG, "Stream is null");
                                }

                                /* download with progress */
                                data = new byte[1024];
                                total = 0;
                                count = 0;
                                // running is added to cancel AsyncTask properly
                                while ((count = bis.read(data)) != -1 && running) {
                                    out.write(data, 0, count);

                                    total += count;

                                    if (fileSize != -1) {
                                        publishProgress((int) ((total * 100) / fileSize));
                                    } else {
                                        publishProgress(50); // no ContentLength was returned
                                    }
                                }

                                // add line seperator to add files together in one file
                                out.write(Constants.LINE_SEPERATOR.getBytes());
                            } catch (Exception e) {
                                Log.e(Constants.TAG, "Exception: " + e);
                                returnCode = RETURN_DOWNLOAD_FAIL;
                                break; // stop for-loop
                            } finally {
                                // flush and close streams
                                try {
                                    if (out != null) {
                                        out.flush();
                                    }
                                    if (bis != null) {
                                        bis.close();
                                    }
                                    if (is != null) {
                                        is.close();
                                    }
                                } catch (Exception e) {
                                    Log.e(Constants.TAG, "Exception on flush and closing streams: "
                                            + e);
                                    e.printStackTrace();
                                }
                            }
                        }
                    } catch (Exception e) {
                        Log.e(Constants.TAG, "Private File can not be created, Exception: " + e);
                        returnCode = RETURN_PRIVATE_FILE;
                    } finally {
                        try {
                            if (out != null) {
                                out.close();
                            }
                        } catch (Exception e) {
                            Log.e(Constants.TAG, "Exception on close of out: " + e);
                            e.printStackTrace();
                        }
                    }
                } else {
                    returnCode = RETURN_NO_CONNECTION;
                }

                return returnCode;
            }

            @Override
            protected void onProgressUpdate(Integer... progress) {
                // update dialog with filename and progress
                if (urlChanged) {
                    Log.d(Constants.TAG, "urlChanged");
                    mDownloadProgressDialog.setMessage(getString(R.string.download_dialog)
                            + Constants.LINE_SEPERATOR + currentURL);
                    urlChanged = false;
                }
                // Log.d(Constants.TAG, "progress: " + progress[0]);
                mDownloadProgressDialog.setProgress(progress[0]);
            }

            @Override
            protected void onPostExecute(Integer result) {
                super.onPostExecute(result);

                Log.d(Constants.TAG, "onPostExecute result: " + result);

                if (result == RETURN_SUCCESS) {
                    mDownloadProgressDialog.dismiss();

                    // Apply files by Apply thread
                    apply();
                } else if (result == RETURN_NO_CONNECTION) {
                    mDownloadProgressDialog.dismiss();

                    AlertDialog alertDialog = new AlertDialog.Builder(mContext).create();
                    alertDialog.setIcon(android.R.drawable.ic_dialog_alert);
                    alertDialog.setTitle(R.string.no_connection_title);
                    alertDialog.setMessage(getString(org.adaway.R.string.no_connection));
                    alertDialog.setButton(getString(R.string.button_close),
                            new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dlg, int sum) {
                                    dlg.dismiss();
                                }
                            });
                    alertDialog.show();
                } else if (result == RETURN_PRIVATE_FILE) {
                    mDownloadProgressDialog.dismiss();

                    AlertDialog alertDialog = new AlertDialog.Builder(mContext).create();
                    alertDialog.setIcon(android.R.drawable.ic_dialog_alert);
                    alertDialog.setTitle(R.string.no_private_file_title);
                    alertDialog.setMessage(getString(org.adaway.R.string.no_private_file));
                    alertDialog.setButton(getString(R.string.button_close),
                            new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dlg, int sum) {
                                    dlg.dismiss();
                                }
                            });
                    alertDialog.show();
                } else { // RETURN_DOWNLOAD_FAIL
                    mDownloadProgressDialog.dismiss();

                    AlertDialog alertDialog = new AlertDialog.Builder(mContext).create();
                    alertDialog.setIcon(android.R.drawable.ic_dialog_alert);
                    alertDialog.setTitle(R.string.download_fail_title);
                    alertDialog.setMessage(getString(org.adaway.R.string.download_fail) + "\n"
                            + currentURL);
                    alertDialog.setButton(getString(R.string.button_close),
                            new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dlg, int sum) {
                                    dlg.dismiss();
                                }
                            });
                    alertDialog.show();
                }
            }
        };

        downloadHostsSources.execute(urls);
    }

    /**
     * Async Thread to parse downloaded hosts files, build one new merged hosts file out of them
     * using the redirection ip from the preferences and apply them using RootTools.
     */
    private void apply() {
        AsyncTask<Void, String, Boolean> apply = new AsyncTask<Void, String, Boolean>() {
            @Override
            protected Boolean doInBackground(Void... unused) {
                try {
                    /* PARSE: parse hosts files to sets of hostnames and comments */
                    publishProgress(getString(R.string.apply_dialog_hostnames));

                    FileInputStream fis = openFileInput(Constants.DOWNLOADED_HOSTS_FILENAME);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(fis));

                    HostsParser parser = new HostsParser(reader, getApplicationContext());
                    HashSet<String> hostnames = parser.getHostnames();
                    LinkedList<String> comments = parser.getComments();

                    fis.close();

                    publishProgress(getString(R.string.apply_dialog_lists));

                    /* READ DATABSE CONTENT */
                    mDatabaseHelper = new DatabaseHelper(mContext);

                    // get whitelist
                    HashSet<String> whitelist = mDatabaseHelper.getAllEnabledWhitelistItems();
                    Log.d(Constants.TAG, "Enabled whitelist: " + whitelist.toString());

                    // get blacklist
                    HashSet<String> blacklist = mDatabaseHelper.getAllEnabledBlacklistItems();
                    Log.d(Constants.TAG, "Enabled blacklist: " + blacklist.toString());

                    // get redirection list
                    HashMap<String, String> redirection = mDatabaseHelper
                            .getAllEnabledRedirectionItems();
                    Log.d(Constants.TAG, "Enabled redirection list: " + redirection.toString());

                    mDatabaseHelper.close();

                    /* BLACKLIST AND WHITELIST */
                    // remove whitelist items
                    hostnames.removeAll(whitelist);

                    // add blacklist items
                    hostnames.addAll(blacklist);

                    /* REDIRECTION LIST: remove hostnames that are in redirection list */
                    HashSet<String> redirectionRemove = new HashSet<String>(redirection.keySet());

                    // remove all redirection hostnames
                    hostnames.removeAll(redirectionRemove);

                    /* BUILD: build one hosts file out of sets and preferences */
                    publishProgress(getString(R.string.apply_dialog_hosts));

                    FileOutputStream fos = openFileOutput(Constants.HOSTS_FILENAME,
                            Context.MODE_PRIVATE);

                    // add adaway header
                    String header = "# This hosts file is generated by AdAway."
                            + Constants.LINE_SEPERATOR
                            + "# Please do not modify it directly, it will be overwritten when AdAway is applied again."
                            + Constants.LINE_SEPERATOR;
                    fos.write(header.getBytes());

                    // write comments from other files to header
                    if (!SharedPrefs.getStripComments(getApplicationContext())) {
                        String headerComment = "# "
                                + Constants.LINE_SEPERATOR
                                + "# The following lines are comments from the downloaded hosts files:";
                        fos.write(headerComment.getBytes());

                        String line;
                        for (String comment : comments) {
                            line = Constants.LINE_SEPERATOR + comment;
                            fos.write(line.getBytes());
                        }

                        fos.write(Constants.LINE_SEPERATOR.getBytes());
                    }

                    String redirectionIP = SharedPrefs.getRedirectionIP(getApplicationContext());

                    // add "127.0.0.1 localhost" entry
                    String localhost = Constants.LINE_SEPERATOR + redirectionIP + " "
                            + Constants.LOCALHOST_HOSTNAME;
                    fos.write(localhost.getBytes());

                    fos.write(Constants.LINE_SEPERATOR.getBytes());

                    // write hostnames
                    String line;
                    for (String hostname : hostnames) {
                        line = Constants.LINE_SEPERATOR + redirectionIP + " " + hostname;
                        fos.write(line.getBytes());
                    }

                    /* REDIRECTION LIST: write redirection items */
                    String redirectionItemHostname;
                    String redirectionItemIP;
                    for (HashMap.Entry<String, String> item : redirection.entrySet()) {
                        redirectionItemHostname = item.getKey();
                        redirectionItemIP = item.getValue();

                        line = Constants.LINE_SEPERATOR + redirectionItemIP + " "
                                + redirectionItemHostname;
                        fos.write(line.getBytes());
                    }

                    fos.close();

                    // delete downloaded hosts file from private storage
                    deleteFile(Constants.DOWNLOADED_HOSTS_FILENAME);

                    /* APPLY: apply hosts file using RootTools in copyHostsFile() */
                    publishProgress(getString(R.string.apply_dialog_apply));

                    // copy build hosts file with RootTools
                    if (!copyHostsFile()) {
                        throw new Exception();
                    }

                    // delete generated hosts file from private storage
                    deleteFile(Constants.HOSTS_FILENAME);
                } catch (Exception e) {
                    Log.e(Constants.TAG, "Exception: " + e);
                    e.printStackTrace();

                    return false;
                }

                return true;
            }

            @Override
            protected void onPreExecute() {
                super.onPreExecute();
                // showDialog(DIALOG_APPLY_PROGRESS);
                mApplyProgressDialog = new ProgressDialog(mContext);
                mApplyProgressDialog.setMessage(getString(R.string.apply_dialog));
                mApplyProgressDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
                mApplyProgressDialog.setCancelable(false);
                mApplyProgressDialog.show();
            }

            @Override
            protected void onProgressUpdate(String... status) {
                mApplyProgressDialog.setMessage(status[0]);
            }

            @Override
            protected void onPostExecute(Boolean result) {
                super.onPostExecute(result);

                if (result) {
                    // removeDialog(DIALOG_APPLY_PROGRESS);
                    mApplyProgressDialog.dismiss();

                    AlertDialog alertDialog = new AlertDialog.Builder(mContext).create();
                    alertDialog.setIcon(android.R.drawable.ic_dialog_info);
                    alertDialog.setTitle(R.string.apply_dialog);
                    alertDialog.setMessage(getString(R.string.apply_success));
                    alertDialog.setButton(getString(R.string.button_close),
                            new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dlg, int sum) {
                                    dlg.dismiss();
                                }
                            });
                    alertDialog.show();

                } else {
                    // removeDialog(DIALOG_APPLY_PROGRESS);
                    mApplyProgressDialog.dismiss();
                    Log.d(Constants.TAG, "Problem!");

                    AlertDialog alertDialog = new AlertDialog.Builder(mContext).create();
                    alertDialog.setIcon(android.R.drawable.ic_dialog_alert);
                    alertDialog.setTitle(R.string.apply_problem_title);
                    alertDialog.setMessage(getString(org.adaway.R.string.apply_problem));
                    alertDialog.setButton(getString(R.string.button_close),
                            new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dlg, int sum) {
                                    dlg.dismiss();
                                }
                            });
                    alertDialog.show();
                }
            }
        };

        apply.execute();
    }

}