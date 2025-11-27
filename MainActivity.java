package com.obdzero.palmexec;

/*
Copyright (c) 2019-2021 PalmSens BV
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

   - Redistributions of source code must retain the above copyright notice,
     this list of conditions and the following disclaimer.
   - Neither the name of PalmSens BV nor the names of its contributors
     may be used to endorse or promote products derived from this software
     without specific prior written permission.
   - This license does not release you from any requirement to obtain separate
     licenses from 3rd party patent holders to use this software.
   - Use of the software either in source or binary form must be connected to,
     run on or loaded to an PalmSens BV component.

DISCLAIMER: THIS SOFTWARE IS PROVIDED BY PALMSENS "AS IS" AND ANY EXPRESS OR
IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO
EVENT SHALL THE REGENTS AND CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA,
OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE,
EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/

/*
Copyright (c) 2025 David Cecil
Modifications to the original PalmSens program include:
-script files are picked from phone storage
-response from PalmSens' Sensit Smart is saved to a csv file
-expanded handling of messages in the response
-Bluetooth functions were deleted
DISCLAIMER: All modifications are provided "AS IS". Any and all consequences of the use
of this modified code are entirely the responsibility of the user, not of David Cecil.
*/


import static android.os.Build.VERSION.SDK_INT;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.ftdi.j2xx.D2xxManager;
import com.ftdi.j2xx.FT_Device;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public class MainActivity extends Activity {

    static final ArrayList<String> mReadings = new ArrayList<>();

    private static final int PERMIT_STORAGE = 1;

    private enum AppState {
        Idle,
        Connecting,
        IdleConnected,
        ScriptRunning,
    }

    private static final String TAG = "MainActivity";

    private final static String[] scriptFiles = {"EIS.txt", "LSV.txt", "OCP.txt", "SWV.txt"};
    private static File fileFolder = null;
    private static File fileResponse = null;
    private boolean folderOk = false;
    private boolean fileOk = false;
    private boolean scriptOk = false;
    private final static SimpleDateFormat fileDate = new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.US);
    private final static SimpleDateFormat dataDateComma = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss,sss", Locale.US);
    private final static SimpleDateFormat dataDateDot = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss.sss", Locale.US);
    private final ArrayList<String> listResponse = new ArrayList<>();
    private final ArrayList<String> listScript = new ArrayList<>();
    private static String strProcess = "NaN";
    private static int nMeasure = 0;
    private static String strMeasure = "NaN";
    private static int nLoop = 0;

    private D2xxManager ftD2xxManager = null;
    private static final String CMD_VERSION_STRING = "t\n";
    private static final String CMD_ABORT_STRING = "Z\n";
    private static final int BAUD_RATE = 230400;                                                    //Baud Rate for EmStat Pico
    private static final int LATENCY_TIMER = 16;                                                    //Read time out for the device in milli seconds.
    private static final int PACKAGE_DATA_VALUE_LENGTH = 8;                                         //Length of the data value in a package
    private static final int OFFSET_VALUE = 0x8000000;                                              //Offset value to receive positive values

    /**
     * The SI unit of the prefixes and their corresponding factors
     */
    private static final Map<Character, Double> SI_PREFIX_FACTORS = new HashMap<>() {{  //The SI unit of the prefixes and their corresponding factors
        put('a', 1e-18);
        put('f', 1e-15);
        put('p', 1e-12);
        put('n', 1e-9);
        put('u', 1e-6);
        put('m', 1e-3);
        put('i', 1.0);
        put(' ', 1.0);
        put('k', 1e3);
        put('M', 1e6);
        put('G', 1e9);
        put('T', 1e12);
        put('P', 1e15);
        put('E', 1e18);
    }};

    private final Handler mHandler = new Handler();
    private FT_Device ftDevice = null;

    private TextView txtResponse;
    private Button btnConnect;
    private Button btnScripts;
    private Button btnStart;
    private Button btnAbort;


    private int nDataPointsReceived = 0;
    private String mVersionResp = "";
    private AppState mAppState;
    private boolean mThreadIsStopped = true;


    @SuppressLint({"MissingInflatedId", "ObsoleteSdkInt"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        txtResponse = findViewById(R.id.txtResponse);
        txtResponse.setMovementMethod(new ScrollingMovementMethod());
        btnConnect = findViewById(R.id.btnConnect);
        btnScripts = findViewById(R.id.btnScripts);
        btnStart = findViewById(R.id.btnStart);
        btnAbort = findViewById(R.id.btnAbort);

        IntentFilter filter = new IntentFilter();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB_MR1) {
            filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB_MR1) {
            filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
            registerReceiver(mUsbReceiverAttach, filter);
        } else {
            registerReceiver(mUsbReceiverAttach, filter);
        }

        try {
            ftD2xxManager = D2xxManager.getInstance(this);
        } catch (D2xxManager.D2xxException ex) {
            Log.e(TAG, "onCreate " + ex);
            AlertDialog.Builder builder = new AlertDialog.Builder(this);                    // Exit the application if D2xxManager instance is null.
            builder.setMessage("Failed to retrieve an instance of D2xxManager. The application is forced to exit.")
                    .setCancelable(false)
                    .setPositiveButton("OK", (dialog, id) -> MainActivity.this.finish());
            AlertDialog alert = builder.create();
            alert.show();
            return;
        }

        setAppState(AppState.Idle);

        checkStorePermission();

        discoverDevice();
    }

    /**
     * Broadcast Receiver for receiving action USB device attached/detached.
     * When the device is attached, calls the method discoverDevices to identify the device.
     * When the device is detached, calls closeDevice()
     */
    private final BroadcastReceiver mUsbReceiverAttach = new BroadcastReceiver() {
        /** @noinspection SynchronizeOnNonFinalField*/
        public void onReceive(Context context, Intent intent) {
            try {
                String action = intent.getAction();
                intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {                             //When device is attached
                    if (discoverDevice())
                        Toast.makeText(context, "Found device", Toast.LENGTH_SHORT).show();
                } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {                      //When device is detached
                    synchronized (ftDevice) {
                        ftDevice.close();
                    }
                    closeDevice();                                                                      //Closes the device
                    btnConnect.setEnabled(false);
                }

            } catch (Exception e) {
                Log.w(TAG, "mUsbReceiverAttach " + e);
                Toast.makeText(context, "no device", Toast.LENGTH_SHORT).show();
            }
        }
    };

    /**
     * <Summary>
     * A runnable that keeps listening for response from the device until device is disconnected.
     * The response is read character by character.
     * When a line of measurement package is read, it is posted on the handler of the main thread (UI thread) for further processing.
     * </Summary>
     */
    private final Runnable mLoop = new Runnable() {
        StringBuilder readLine = new StringBuilder();

        /** @noinspection SynchronizeOnNonFinalField*/
        @Override
        public void run() {
            int readSize;
            byte[] rbuf = new byte[1];
            mThreadIsStopped = false;
            try {
                while (!mThreadIsStopped && ftDevice != null) {
                    synchronized (ftDevice) {
                        readSize = ftDevice.getQueueStatus();                    //Retrieves the size of the available data on the device
                        if (readSize > 0) {
                            ftDevice.read(rbuf, 1);                      //Reads one character from the device
                            String rchar = new String(rbuf);
                            readLine.append(rchar);                             //Forms a line of response by appending the character read

                            if (rchar.equals("\n")) {                           //When a new line '\n' is read, the line is sent for processing
                                mHandler.post(new Runnable() {
                                    final String line = readLine.toString();

                                    @Override
                                    public void run() {
                                        processResponse(line);                  //Calls the method to process the measurement package
                                    }
                                });
                                readLine = new StringBuilder();                 //Resets the readLine to store the next measurement package
                            }
                        } // end of if(readSize>0)
                    }// end of synchronized
                }
            } catch (Exception ignored) {
            }
        }
    };

    /**
     * <Summary>
     * For Android 10 or less storage permission is required in order to read script files from and
     * store results in phone storage.
     * This method, and the next, checks if PalmExec has storage permission and if not
     * it request that permission be granted by the user.
     * </Summary>
     */
    private void checkStorePermission() {
        if (SDK_INT < 30) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                // Permission is not granted
                // No explanation needed; request the permission
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        PERMIT_STORAGE);
                // request Code PERMIT_STORAGE is an app-defined int constant.
                // The callback method, onRequestPermissionsResult,
                // gets the result of the request.

            }
        } else {
            createPSFolder();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMIT_STORAGE) {
            // If request is cancelled, the result arrays are empty.
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                createPSFolder();
            } else {
                Toast.makeText(this, "Storage permission not granted. Cannot access scripts", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        discoverDevice();
        updateView();
    }

    /**
     * <Summary>
     * Unregisters the broadcast receiver(s) to enable garbage collection.
     * </Summary>
     */
    @Override
    protected void onDestroy() {
        Log.d(TAG, "onDestroy: called.");
        super.onDestroy();
        unregisterReceiver(mUsbReceiverAttach);
    }

    /**
     * <Summary>
     * Copy the standard script files from assets to Download/PSData in the phone
     * This and the next two methods
     * </Summary>
     */
    public void createPSFolder() {
        String state = Environment.getExternalStorageState();
        if (Environment.MEDIA_MOUNTED.equals(state)) {
            fileFolder = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath() +
                    "/PSData");
            if (!fileFolder.exists()) {
                if (fileFolder.mkdirs()) {
                    folderOk = true;
                    copyScripts();
                } else {
                    folderOk = false;
                    Toast.makeText(this, "Data folder " + fileFolder.toString() + " could not be created.", Toast.LENGTH_SHORT).show();
                }
            } else {
                folderOk = true;
                copyScripts();
            }
        } else {
            folderOk = false;
            Toast.makeText(this, "No access to storage on this phone.", Toast.LENGTH_SHORT).show();
        }
    }

    public void copyScripts() {
        InputStream in;
        OutputStream out;
        for (String script : scriptFiles) {
            try {
                File fileName = new File(fileFolder + "/" + script);
                if (!fileName.exists()) {
                    in = getAssets().open(script);
                    out = Files.newOutputStream(fileName.toPath());
                    copyFile(in, out);
                    in.close();
                    out.flush();
                    out.close();
                    if (fileName.exists())
                        MediaScannerConnection.scanFile(getApplicationContext(),
                                new String[]{
                                        fileName.toString()},
                                null,
                                (path, uri) -> Log.i(TAG,
                                        "file was scanned successfully: " + uri));
                }
            } catch (IOException e) {
                Log.w(TAG, "copyScripts " + e);
                Toast.makeText(this, script + " not found in assets.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private static void copyFile(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[1024];
        int read;
        while (( read = in.read(buffer) ) != -1) {
            out.write(buffer, 0, read);
        }
    }

    /**
     * <Summary>
     * Looks up the device info list using the D2xxManager to check if a USB device is connected.
     * </Summary>
     *
     * @return A boolean indicating if any device is found
     */
    private boolean discoverDevice() {
        int devCount;
        devCount = ftD2xxManager.createDeviceInfoList(this);

        D2xxManager.FtDeviceInfoListNode[] deviceList = new D2xxManager.FtDeviceInfoListNode[devCount];
        ftD2xxManager.getDeviceInfoList(devCount, deviceList);

        if (devCount > 0) {
            btnConnect.setEnabled(true);
            return true;
        } else {
            btnConnect.setEnabled(false);
            return false;
        }
    }

    private void setAppState(AppState state) {
        mAppState = state;
        updateView();
    }

    /**
     * <Summary>
     * Enables/disables the buttons and updates the UI
     * </Summary>
     */
    @SuppressLint("SetTextI18n")
    private void updateView() {
        switch (mAppState) {
            case Idle:
                btnConnect.setText("Connect");
                btnConnect.setEnabled(discoverDevice());
                btnScripts.setEnabled(true);
                btnStart.setEnabled(false);
                btnAbort.setEnabled(false);
                break;

            case Connecting:
                btnConnect.setText("Connect");
                btnConnect.setEnabled(false);
                btnScripts.setEnabled(false);
                btnStart.setEnabled(false);
                btnAbort.setEnabled(false);
                break;

            case IdleConnected:
                btnConnect.setText("Disconnect");
                btnConnect.setEnabled(true);
                btnScripts.setEnabled(true);
                btnStart.setEnabled(scriptOk);
                btnAbort.setEnabled(false);
                break;

            case ScriptRunning:
                btnConnect.setText("Disconnect");
                btnConnect.setEnabled(true);
                btnScripts.setEnabled(false);
                btnStart.setEnabled(false);
                btnAbort.setEnabled(true);
                break;
        }
    }

    /**
     * <Summary>
     * Opens the device and calls the method to set the device configurations.
     * Also starts a runnable thread (mLoop) that keeps listening to the response until device is disconnected.
     * </Summary>
     *
     * @return A boolean to indicate if the device was opened and configured.
     */
    private boolean openDevice() {
        if (ftDevice != null) {
            ftDevice.close();
        }

        ftDevice = ftD2xxManager.openByIndex(this, 0);

        if (ftDevice.isOpen()) {
            if (mThreadIsStopped) {
                SetConfig();                                                                        //Configures the port with necessary parameters
                ftDevice.purge((byte) ( D2xxManager.FT_PURGE_TX | D2xxManager.FT_PURGE_RX ));         //Purges data from the device's TX/RX buffer
                ftDevice.restartInTask();                                                           //Resumes the driver issuing USB in requests
                new Thread(mLoop).start();                                                          //Start parsing thread
                return true;
            } else {
                return false;
            }
        } else {
            return false;
        }
    }

    /**
     * <Summary>
     * Sets the device configuration properties like Baudrate (230400), databits (8), stop bits (1), parity (None) and bit mode.
     * </Summary>
     */
    private void SetConfig() {
        byte dataBits = D2xxManager.FT_DATA_BITS_8;
        byte stopBits = D2xxManager.FT_STOP_BITS_1;
        byte parity = D2xxManager.FT_PARITY_NONE;

        if (!ftDevice.isOpen()) {
            Log.e(TAG, "SetConfig: device not open");
            return;
        }
        // configures the port, reset to UART mode for 232 devices
        ftDevice.setBitMode((byte) 0, D2xxManager.FT_BITMODE_RESET);
        ftDevice.setBaudRate(BAUD_RATE);
        ftDevice.setLatencyTimer((byte) LATENCY_TIMER);
        ftDevice.setDataCharacteristics(dataBits, stopBits, parity);
    }

    /**
     * <Summary>
     * Sends the version command to verify if the connected device is Sensit Smart.
     * </Summary>
     */
    private void sendVersionCmd() {
        //Send newline to clear command buf on pico, in case there was invalid data in it
        writeToDevice("\n");

        //After some time, send version command (needed if the Sensit Smart had received an invalid command)
        Handler handler = new Handler();
        handler.postDelayed(() -> {
            mVersionResp = "";
            writeToDevice(CMD_VERSION_STRING);
        }, 200);
    }

    /**
     * <Summary>
     * Verifies if the device connected is Sensit Smart by checking if the version response contains "esp"
     * </Summary>
     *
     * @param versionStringLine The response string to be verified.
     */
    private void verifySensit(String versionStringLine) {
        mVersionResp += versionStringLine;
        if (mVersionResp.contains("*\n")) {
            if (mVersionResp.contains("tespico")) {
                Toast.makeText(this, "Connected to Sensit.", Toast.LENGTH_SHORT).show();
                setAppState(AppState.IdleConnected);
            } else {
                Toast.makeText(this, "Failed to connect to Sensit.", Toast.LENGTH_SHORT).show();
                setAppState(AppState.Idle);
                closeDevice();
            }
        }
    }

    /**
     * <Summary>
     * Select a method script from Downloads/PSData
     * </Summary>
     */
    private void showScriptChooser() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");

        try {
            startActivityForResult(Intent.createChooser(intent, "Select a file"),
                    100);
        } catch (Exception e) {
            Log.w(TAG, "showFileChooser " + e);
            Toast.makeText(this, "Please install a file manager.", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * <Summary>
     * Return the script file uri, open the script and transfer the lines to an array.
     * </Summary>
     */
    @SuppressLint("SetTextI18n")
    @Override
    protected void onActivityResult(int requestCode, int resultCode,
                                    @Nullable @org.jetbrains.annotations.Nullable Intent data) {
        scriptOk = false;
        listScript.clear();
        if (requestCode == 100 && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                try {
                    InputStream script = this.getContentResolver().openInputStream(uri);
                    InputStreamReader chapter = new InputStreamReader(script);
                    BufferedReader lineReader = new BufferedReader(chapter);
                    String line;
                    int j = 0;
                    // read every line of the file into the line-variable, one line at the time
                    while (( line = lineReader.readLine() ) != null) {
                        j = line.length();
                        line += "\n";
                        txtResponse.append(line);
                        if (!line.contains("#")) listScript.add(line);
                    }
                    if (j > 0) listScript.add("\n");
                    lineReader.close();
                    chapter.close();
                    if (script != null) {
                        script.close();
                    }
                    scriptOk = true;
                } catch (Exception e) {
                    Log.w(TAG, "onActivityResult " + e);
                }
            }
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    /**
     * <Summary>
     * Reads from the script file and writes it to the device.
     * </Summary>
     *
     * @return A boolean to indicate if the script file was sent successfully to the device.
     */
    private boolean sendScript() {
        if (!listScript.isEmpty()) {
            listResponse.clear();
            listResponse.add("DateTime;Process;Measure;nMeasure;nResponse;Identifier1;Variable1;Identifier2;Variable2;Id3/Status;Var3/Range\n");
            for (String line : listScript) {
                writeToDevice(line);
            }
            return true;
        } else {
            return false;
        }
    }

    /**
     * <Summary>
     * Writes the input data to the USB device
     * </Summary>
     *
     * @param line one line in a MethodSCRIPT to be written
     * @return A boolean indicating if the write operation succeeded.
     */
    private boolean writeToDevice(String line) {
        int bytesWritten;
        if (ftDevice != null) {
            //noinspection SynchronizeOnNonFinalField
            synchronized (ftDevice) {
                if (ftDevice.isOpen()) {
                    byte[] writeByte = line.getBytes();
                    //Writes to the device
                    bytesWritten = ftDevice.write(writeByte, line.length());
                    //Verifies if the bytes written equals the total number of bytes sent
                    if (bytesWritten == writeByte.length) {
                        return true;
                    } else {
                        Log.e(TAG, "onClickWrite : Device write failed");
                        return false;
                    }
                } else {
                    Log.e(TAG, "onClickWrite : Device is not open");
                    return false;
                }
            }
        } else {
            Log.e(TAG, "onClickWrite : Device is null");
            return false;
        }
    }

    /**
     * <Summary>
     * Aborts the script
     * </Summary>
     */
    private void abortScript() {
        if (writeToDevice(CMD_ABORT_STRING)) {
            setAppState(AppState.IdleConnected);
            Toast.makeText(this, "Method Script aborted", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * <Summary>
     * Aborts active script if any and closes the device.
     * Updates the view buttons.
     * </Summary>
     */
    void closeDevice() {
        mThreadIsStopped = true;
        if (ftDevice != null && ftDevice.isOpen()) {
            if (mAppState == AppState.ScriptRunning) {
                abortScript();
            }
            ftDevice.close();
        }
        setAppState(AppState.Idle);
    }

    /**
     * <Summary>
     * Processes the measurement packages from the device.
     * </Summary>
     *
     * @param line A complete line of response read from the device.
     */
    private void processResponse(String line) {
        switch (mAppState) {
            case Connecting:
                verifySensit(line);          //Calls the verifySensit method to verify if the device is EmStat Pico
                break;
            case ScriptRunning:
                processReceivedPackage(line);   //Calls the method to process the received measurement package
                break;
        }
    }

    /**
     * <Summary>
     * Processes the measurement package from the Sensit Smart, shows the package
     * on the screen and saves the package for later storage on the phone.
     * The first character of each line of the package identifies the type of line.
     * START_LOOP('L'),
     * END_LOOP('+'),
     * BEGIN_VERSION('t'),
     * BEGIN_RESPONSE('e'),
     * MEASURING('M'),
     * COMMENT('T'),
     * BEGIN_PACKETS('P'),
     * END_MEAS_LOOP('*'),
     * EMPTY_LINE('\n'),
     * ERROR('!'),
     * ABORTED('Z');
     * </Summary>
     * * @param readLine A measurement package read from the device.
     */
    private void processReceivedPackage(String readLine) {
        if (readLine != null) {
            switch (readLine.charAt(0)) {
                case '\n':
                    setAppState(AppState.IdleConnected);
                    storeResponse();
                    Toast.makeText(this, "Script completed", Toast.LENGTH_LONG).show();
                    break;
                case '*':
                    txtResponse.append("Measurement completed.\n\n");
                    storeResponse();
                    break;
                case 'T':
                    if (readLine.charAt(1) == 'p') {            //if the second character in a comment is "p" then this is the start of a process.
                        strProcess = readLine.substring(2, 5);  //Processes are used to sort the stored data after the data has been transferred to a PC
                    }                                           //In this way process comments can help with the later analysis of the response from the Sensit
                    break;
                case 'M':
                    nMeasure++;                             //The measurement number aids in sorting the data.
                    nDataPointsReceived = 0;                                  //Increments the number of data points if the read line contains the header char 'P
                    switch (readLine.substring(1, 5)) {
                        case "0000":
                            strMeasure = "LSV";
                            txtResponse.append("Idx    V       A      Status  A-range\n");
                            break;
                        case "0001":
                            strMeasure = "DPV";
                            txtResponse.append("Idx    V       A      Status  A-range\n");
                            break;
                        case "0002":
                            strMeasure = "SWV";
                            txtResponse.append("Idx    V       A      Status  A-range\n");
                            break;
                        case "0003":
                            strMeasure = "NPV";
                            txtResponse.append("Idx    V       A      Status  A-range\n");
                            break;
                        case "0004":
                            strMeasure = "ACV";
                            txtResponse.append("Idx   dc-V    ac-A\n");
                            break;
                        case "0005":
                            strMeasure = "CLV";
                            txtResponse.append("Idx    V       A      Status  A-range\n");
                            break;
                        case "0007":
                            strMeasure = "CHA";
                            txtResponse.append("Idx     V       A     Status  A-range\n");
                            break;
                        case "0008":
                            strMeasure = "PAD";
                            txtResponse.append("Idx\n");
                            break;
                        case "0009":
                            strMeasure = "FCA";
                            txtResponse.append("Idx\n");
                            break;
                        case "000A":
                            strMeasure = "CHP";
                            txtResponse.append("Idx      A       V\n");
                            break;
                        case "000B":
                            strMeasure = "OCP";
                            txtResponse.append("Idx    V\n");
                            break;
                        case "000D":
                            strMeasure = "EIS";
                            txtResponse.append("Idx    Hz     realOhm  imagOhm\n");
                            break;
                        case "000E":
                            strMeasure = "GES";
                            txtResponse.append("Idx    Hz     realOhm  imagOhm\n");
                            break;
                        case "000F":
                            strMeasure = "LSP";
                            txtResponse.append("Idx    A       V\n");
                            break;
                        case "0010":
                            strMeasure = "FCP";
                            txtResponse.append("Idx\n");
                            break;
                        case "0011":
                            strMeasure = "CAX";
                            txtResponse.append("Idx\n");
                            break;
                        case "0012":
                            strMeasure = "CPX";
                            txtResponse.append("Idx\n");
                            break;
                        case "0013":
                            strMeasure = "OCX";
                            txtResponse.append("Idx\n");
                            break;
                        default:
                            strMeasure = "NaN";
                            txtResponse.append("Idx\n");
                            break;
                    }
                    break;
                case 'P':
                    nDataPointsReceived++;                                  //Increments the number of data points if the read line contains the header char 'P
                    parsePackageLine(readLine);                              //Parses the line read
                    break;
                case '!':
                    txtResponse.append("Error " + readLine + "\n");
                    listResponse.add("Error " + readLine + "\n");
                    abortScript();
                    break;
                case 'Z':
                    txtResponse.append("Measurement aborted.\n");
                    listResponse.add("Measurement aborted.\n");
                    break;
                case 'L':
                    nLoop++;
                    txtResponse.append("Loop" + nLoop + "\n");
                    break;
                case '+':
                    nLoop--;
                    txtResponse.append("Loop" + nLoop + "\n");
                    break;
                default:
                    break;
            }
        } else {
            txtResponse.append("\n");
        }
    }

    /**
     * <summary>
     * Parses a measurement data package and adds the parsed data values to their corresponding arrays
     * </summary>
     *
     * @param packageLine The measurement data package to be parsed
     */
    private void parsePackageLine(String packageLine) {
        String[] variables;
        String variableIdentifier;
        String dataValue;


        int startingIndex = packageLine.indexOf('P');                            //Identifies the beginning of the measurement data package
        String responsePackageLine = packageLine.substring(startingIndex + 1);   //Removes the beginning character 'P'
        startingIndex = 0;

        txtResponse.append(String.format(Locale.getDefault(), "%4d", nDataPointsReceived));

        String testNumber = String.format(Locale.getDefault(), "%1$ 2.1e", 1.1);
        if (testNumber.contains(".")) listResponse.add(dataDateDot.format(new Date())); else listResponse.add(dataDateComma.format(new Date()));
        listResponse.add(";" + strProcess + ";" + strMeasure + ";" + nMeasure + ";" + nDataPointsReceived);

        variables = responsePackageLine.split(";");                     //The data values are separated by the delimiter ';'
        for (String variable : variables) {
            variableIdentifier = variable.substring(startingIndex, 2);          //The String (2 characters) that identifies the measurement variable
            dataValue = variable.substring(startingIndex + 2, startingIndex + 2 + PACKAGE_DATA_VALUE_LENGTH);
            double dataValueWithPrefix = parseParamValues(dataValue);           //Parses the variable values and returns the actual values with their corresponding SI unit prefixes
            switch (variableIdentifier) {
                case "da":                                                      //Measured potential
                case "ba":                                                      //Measured current
                case "eb":                                                      //Time
                case "dc":                                                      //Set value for frequency
                case "cb":                                                      //Measured impedance
                case "cc":                                                      //Measured real part of complex impedance
                case "cd":                                                      //Measured imaginary part of complex impedance
                default:
                    mReadings.add(variableIdentifier + ";" + String.format(Locale.getDefault(), "%1$-1.5e ", dataValueWithPrefix));
                    break;
            }
            txtResponse.append(" " + String.format(Locale.getDefault(), "%1$ 2.1e", dataValueWithPrefix));
            listResponse.add(";" + variableIdentifier + ";" + String.format(Locale.getDefault(), "%1$1.6e", dataValueWithPrefix));
            if (variableIdentifier.equals("ba")) {
                if (variable.length() > 10 && variable.charAt(10) == ',') {
                    parseMetaDataValues(variable.substring(11));                    //Parses the metadata values in the variable, if any
                } else {
                    listResponse.add(";;");
                }
            }
        }
        txtResponse.append("\n");
        listResponse.add("\n");
    }

    /**
     * <Summary>
     * Parses the data value package and appends the respective prefixes
     * </Summary>
     *
     * @param paramValueString The data value package to be parsed
     * @return The actual data value (double) after appending the unit prefix
     */
    private double parseParamValues(String paramValueString) {
        if (Objects.equals(paramValueString, "     nan"))
            return Double.NaN;
        char strUnitPrefix = paramValueString.charAt(7);                        //Identifies the SI unit prefix from the package at position 8
        String strvalue = paramValueString.substring(0, 7);                     //Retrieves the value of the variable from the package
        int value = Integer.parseInt(strvalue, 16);                        //Converts the hex value to int
        double paramValue = value - OFFSET_VALUE;                                //Values offset to receive only positive values
        if (SI_PREFIX_FACTORS.get(strUnitPrefix) != null)
            //noinspection DataFlowIssue
            return paramValue * SI_PREFIX_FACTORS.get(strUnitPrefix);              //Returns the actual data value after appending the SI unit prefix
        else {
            return 0;
        }
    }

    /**
     * <Summary>
     * Parses the metadata values of the variable, if any.
     * The first character in each meta data value specifies the type of data.
     * 1 - 1 char hex mask holding the status (0 = OK, 2 = overload, 4 = underload, 8 = overload warning (80% of max))
     * 2 - 2 chars hex holding the current range index. First bit high (0x80) indicates a high speed mode cr.
     * 4 - 1 char hex holding the noise value
     * </Summary>
     *
     * @param packageMetaData The metadata values from the package to be parsed.
     */
    private void parseMetaDataValues(String packageMetaData) {
        String[] metaDataValues;
        metaDataValues = packageMetaData.split(",");
        for (String metaData : metaDataValues) {
            switch (metaData.charAt(0)) {
                case '1':
                    getReadingStatusFromPackage(metaData);
                    break;
                case '2':
                    getCurrentRangeFromPackage(metaData);
                    break;
                default:
                    break;
            }
        }
    }

    /**
     * <Summary>
     * Parses the reading status from the package. 1 char hex mask holding the status (0 = OK, 2 = overload, 4 = underload, 8 = overload warning (80% of max))
     * </Summary>
     *
     * @param metaDatastatus The status metadata to be parsed
     */
    private void getReadingStatusFromPackage(String metaDatastatus) {
        String strStatus;
        switch (metaDatastatus.charAt(1)) {
            case '0':
                strStatus = "Ok";
                break;
            case '1':
                strStatus = "Tout";
                break;
            case '2':
                strStatus = "Over";
                break;
            case '4':
                strStatus = "Under";
                break;
            case '8':
                strStatus = ">80%";
                break;
            default:
                strStatus = "NaN";
                break;
        }
        txtResponse.append(" " + String.format("%-5s", strStatus));
        listResponse.add(";" + strStatus);
    }

    /**
     * <summary>
     * Displays the string corresponding to the input cr int
     * </summary>
     */
    private void getCurrentRangeFromPackage(String metaRange) {
        String strRange;
        switch (( Integer.parseInt(metaRange.substring(1, 3), 16) )) {
            case 1:
                strRange = "100nA";
                break;
            case 2:
                strRange = "2uA";
                break;
            case 3:
                strRange = "4uA";
                break;
            case 4:
                strRange = "8uA";
                break;
            case 5:
                strRange = "16uA";
                break;
            case 6:
                strRange = "32uA";
                break;
            case 7:
                strRange = "63uA";
                break;
            case 8:
                strRange = "125uA";
                break;
            case 9:
                strRange = "250uA";
                break;
            case 10:
                strRange = "500uA";
                break;
            case 11:
                strRange = "1mA";
                break;
            case 128:
                strRange = "5mA";
                break;
            case 129:
                strRange = "100nAhs";
                break;
            case 130:
                strRange = "1uAhs";
                break;
            case 131:
                strRange = "6uAhs";
                break;
            case 132:
                strRange = "13uAhs";
                break;
            case 133:
                strRange = "25uAhs";
                break;
            case 134:
                strRange = "50uAhs";
                break;
            case 135:
                strRange = "100uAhs";
                break;
            case 136:
                strRange = "200uAhs";
                break;
            case 137:
                strRange = "1mAhs";
                break;
            case 138:
                strRange = "5mAhs";
                break;
            default:
                strRange = metaRange.substring(1, 3);
                break;
        }
        txtResponse.append(" " + strRange);
        listResponse.add(";" + strRange);
    }

    /**
     * <summary>
     * Stores the string array listResponse to a file named Downloads/PSData_YYYY_MM_DD_HH_MM_SS.csv
     * </summary>
     */
    public void storeResponse() {
        if (fileOk) {
            storing();
        } else {
            if (!folderOk) {
                String state = Environment.getExternalStorageState();
                if (Environment.MEDIA_MOUNTED.equals(state)) {
                    fileFolder = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath() +
                            "/PSData");
                    if (!fileFolder.exists()) {
                        if (fileFolder.mkdirs()) {
                            folderOk = true;
                        } else {
                            Toast.makeText(this, "Data folder " + fileFolder.toString() + " could not be created.", Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        folderOk = true;
                    }
                } else {
                    folderOk = false;
                }
            }

            if (folderOk) {
                String file = "PSData_" + fileDate.format(new Date()) + ".csv";
                fileResponse = new File(fileFolder, file);
                storing();
            }
        }
    }

    /**
     * <summary>
     * Writes the data to the files
     * </summary>
     */
    private void storing() {
        if (!listResponse.isEmpty()) {
            try {
                FileOutputStream out = new FileOutputStream(fileResponse, true);
                OutputStreamWriter osw = new OutputStreamWriter(out);
                for (String str : listResponse) osw.write(str);
                osw.flush();
                osw.close();
                listResponse.clear();
                fileOk = true;
                Toast.makeText(this, "One measurement has been stored", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Log.w(TAG, "storing " + e);
                fileOk = false;
                Toast.makeText(this, "One measurement could not be stored", Toast.LENGTH_SHORT).show();
                abortScript();
            }
        } else {
            Toast.makeText(this, "No more data to store", Toast.LENGTH_SHORT).show();
        }
    }

//region Events

    /**
     * <Summary>
     * Opens the device on click of connect and sends the version command to verify if the device is EmStat Pico.
     * </Summary>
     *
     * @param view btnConnect
     */
    public void onClickConnect(View view) {
        switch (mAppState) {
            case Idle:
                try {
                    if (openDevice()) {
                        setAppState(AppState.Connecting);
                        sendVersionCmd();
                    }
                } catch (Exception e) {
                    Log.w(TAG, "onClickConnect " + e);
                    Toast.makeText(this, "Connection failed, please restart Palmexec.", Toast.LENGTH_SHORT).show();
                }
                break;
            case IdleConnected:
            case ScriptRunning:
                closeDevice();                                                    //Disconnects the device
                setAppState(AppState.Idle);
                Toast.makeText(this, "Device is disconnected.", Toast.LENGTH_SHORT).show();
                break;
        }
    }

    /**
     * <Summary>
     * Opens the script file using the file chooser.
     * </Summary>
     *
     * @param view btnScripts
     */
    public void onClickScripts(View view) {
        switch (mAppState) {
            case Idle:
            case IdleConnected:
                showScriptChooser();
                break;
            case Connecting:
            case ScriptRunning:
                break;
        }
    }

    /**
     * <Summary>
     * Calls the method to abort the MethodSCRIPT.
     * </Summary>
     *
     * @param view btnAbort
     */
    public void onClickAbort(View view) {
        abortScript();
    }

    /**
     * <Summary>
     * Calls the method to send the MethodSCRIPT.
     * </Summary>
     *
     * @param view btnStart
     */
    public void onClickStart(View view) {
        if (sendScript()) {
            setAppState(AppState.ScriptRunning);
        } else {
            setAppState(AppState.IdleConnected);
        }
    }
//endregion

}


