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
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.util.Log;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
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

    private static final int PERMIT_STORAGE = 1;

    private enum AppState {
        Idle,
        Connecting,
        IdleConnected,
        ScriptRunning,
    }

    private static final String TAG = "MainActivity";

    private final static String[] scriptFiles = {"CV01.txt", "EIS01.txt", "LSV01.txt", "OCP01.txt", "SWV01.txt"};
    private static File fileFolder = null;
    private static File fileResponse = null;
    private static File filePeak = null;
    private boolean folderOk = false;
    private boolean fileOk = false;
    private boolean scriptOk = false;
    private final static SimpleDateFormat fileDate = new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.US);
    private static SimpleDateFormat dataDate;
    private final ArrayList<String> listResponse = new ArrayList<>();
    private final ArrayList<String> listPeak = new ArrayList<>();
    private final ArrayList<String> listScript = new ArrayList<>();
    private ArrayList<String> listDisplay = new ArrayList<>();
    private static String strProcess = "NaN";
    private static int nMeasure = 0;
    private static String strMeasure = "NaN";
    private static int nLoop = 0;
    private static final double[] currents = new double[5];
    private static final double[] potentials = new double[5];

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

    private Button btnConnect;
    private Button btnScripts;
    private Button btnStart;
    private Button btnAbort;
    private ListView list = null;


    private int nDataPoints = 0;
    private String mVersionResp = "";
    private AppState mAppState;
    private boolean mThreadIsStopped = true;


    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);


        list = findViewById(R.id.listMeasure);

        btnConnect = findViewById(R.id.btnConnect);
        btnScripts = findViewById(R.id.btnScripts);
        btnStart = findViewById(R.id.btnStart);
        btnAbort = findViewById(R.id.btnAbort);

        btnConnect.setText(R.string.connect);
        btnScripts.setText(R.string.script);
        btnStart.setText(R.string.start);
        btnAbort.setText(R.string.abort);


        btnConnect.setOnClickListener(v -> onClickConnect());
        btnScripts.setOnClickListener(v -> onClickScripts());
        btnStart.setOnClickListener(v -> onClickStart());
        btnAbort.setOnClickListener(v -> onClickAbort());

        String testNumber = String.format(Locale.getDefault(), "%1$ 2.1e", 1.1);
        if (testNumber.contains("."))
            dataDate = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss.SSS", Locale.US);
        else
            dataDate = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss,SSS", Locale.US);

        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(mUsbReceiverAttach, filter);

        try {
            ftD2xxManager = D2xxManager.getInstance(this);
        } catch (D2xxManager.D2xxException ex) {
            Log.e(TAG, "onCreate " + ex);
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            // Exit the application if D2xxManager instance is null.
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
     * Copy the standard script files from assets to Download/PalmData in the phone
     * This and the next two methods
     * </Summary>
     */
    public void createPSFolder() {
        String state = Environment.getExternalStorageState();
        if (Environment.MEDIA_MOUNTED.equals(state)) {
            fileFolder = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath() +
                    "/PalmData");
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

    private void updateView() {
        switch (mAppState) {
            case Idle:
                btnConnect.setText(R.string.connect);
                btnConnect.setEnabled(discoverDevice());
                btnScripts.setEnabled(true);
                btnStart.setEnabled(false);
                btnAbort.setEnabled(false);
                break;

            case Connecting:
                btnConnect.setText(R.string.connect);
                btnConnect.setEnabled(false);
                btnScripts.setEnabled(false);
                btnStart.setEnabled(false);
                btnAbort.setEnabled(false);
                break;

            case IdleConnected:
                btnConnect.setText(R.string.disconnect);
                btnConnect.setEnabled(true);
                btnScripts.setEnabled(true);
                btnStart.setEnabled(scriptOk);
                btnAbort.setEnabled(false);
                break;

            case ScriptRunning:
                btnConnect.setText(R.string.disconnect);
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
     * Select a method script from Downloads/PalmData
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
                        updateResponse(line);
                        line += "\n";
                        listScript.add(line);
                    }
                    updateResponse("");
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
            listResponse.add("DateTime;Process;Measure;nMeasure;nDataPoints;Identifier1;Variable1;Identifier2;Variable2;Id3Status;Var3Range\n");
            listPeak.clear();
            listPeak.add("DateTime;Process;Measure;nMeasure;nDataPoints;Potentials;Currents\n");
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
                    updateResponse("Measurement stored in downloads/PalmData.");
                    storeResponse();
                    if (strMeasure.equals("LSV") || strMeasure.equals("CLV")) {
                        currents[0] = 0;
                        currents[1] = 0;
                        currents[2] = 0;
                        currents[3] = 0;
                        currents[4] = 0;
                    }
                    break;
                case 'T':
                    if (readLine.charAt(1) == 'p') {            //if the second character in a comment is "p" then this is the start of a process.
                        strProcess = readLine.substring(2, 5);  //Processes are used to sort the stored data after the data has been transferred to a PC
                    }                                           //In this way process comments can help with the later analysis of the response from the Sensit
                    break;
                case 'M':
                    nMeasure++;                                  //The measurement number aids in sorting the data.
                    nDataPoints = 0;                             //Increments the number of data points if the read line contains the header char 'P
                    switch (readLine.substring(1, 5)) {
                        case "0000":
                            strMeasure = "LSV";
                            updateResponse("Idx    V       A      Status  A-range");
                            break;
                        case "0001":
                            strMeasure = "DPV";
                            updateResponse("Idx    V       A      Status  A-range");
                            break;
                        case "0002":
                            strMeasure = "SWV";
                            updateResponse("Idx    V       A      Status  A-range");
                            break;
                        case "0003":
                            strMeasure = "NPV";
                            updateResponse("Idx    V       A      Status  A-range");
                            break;
                        case "0004":
                            strMeasure = "ACV";
                            updateResponse("Idx   dc-V    ac-A");
                            break;
                        case "0005":
                            strMeasure = "CLV";
                            updateResponse("Idx    V       A      Status  A-range");
                            break;
                        case "0007":
                            strMeasure = "CHA";
                            updateResponse("Idx     V       A     Status  A-range");
                            break;
                        case "0008":
                            strMeasure = "PAD";
                            updateResponse("Idx");
                            break;
                        case "0009":
                            strMeasure = "FCA";
                            updateResponse("Idx");
                            break;
                        case "000A":
                            strMeasure = "CHP";
                            updateResponse("Idx      A       V");
                            break;
                        case "000B":
                            strMeasure = "OCP";
                            updateResponse("Idx    V");
                            break;
                        case "000D":
                            strMeasure = "EIS";
                            updateResponse("Idx    Hz     realOhm  imagOhm");
                            break;
                        case "000E":
                            strMeasure = "GES";
                            updateResponse("Idx    Hz     realOhm  imagOhm");
                            break;
                        case "000F":
                            strMeasure = "LSP";
                            updateResponse("Idx    A       V");
                            break;
                        case "0010":
                            strMeasure = "FCP";
                            updateResponse("Idx");
                            break;
                        case "0011":
                            strMeasure = "CAX";
                            updateResponse("Idx");
                            break;
                        case "0012":
                            strMeasure = "CPX";
                            updateResponse("Idx");
                            break;
                        case "0013":
                            strMeasure = "OCX";
                            updateResponse("Idx");
                            break;
                        default:
                            strMeasure = "NaN";
                            updateResponse("Idx");
                            break;
                    }
                    break;
                case 'P':
                    nDataPoints++;                                  //Increments the number of data points if the read line contains the header char 'P
                    parsePackageLine(readLine);                              //Parses the line read
                    break;
                case '!':
                    updateResponse("Error " + readLine);
                    listResponse.add(dataDate.format(new Date()) + " Error " + readLine + "\n");
                    storeResponse();
                    break;
                case 'Z':
                    updateResponse("Measurement aborted.");
                    listResponse.add(dataDate.format(new Date()) + " Measurement aborted.\n");
                    storeResponse();
                    break;
                case 'L':
                    nLoop++;
                    Toast.makeText(this, "Loop " + nLoop + " started", Toast.LENGTH_SHORT).show();
                    break;
                case '+':
                    Toast.makeText(this, "Loop " + nLoop + " completed", Toast.LENGTH_SHORT).show();
                    nLoop--;
                    break;
                default:
                    break;
            }
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
        double dataValueWithPrefix;

        int startingIndex = packageLine.indexOf('P');                                       //Identifies the beginning of the measurement data package
        String responsePackageLine = packageLine.substring(startingIndex + 1);    //Removes the beginning character 'P'
        startingIndex = 0;

        StringBuilder display = new StringBuilder(String.format(Locale.getDefault(), "%4d", nDataPoints));
        listResponse.add(dataDate.format(new Date()) + ";" + strProcess + ";" + strMeasure + ";" + nMeasure + ";" + nDataPoints);


        variables = responsePackageLine.split(";");                       //The data values are separated by the delimiter ';'
        for (String variable : variables) {
            variableIdentifier = variable.substring(startingIndex, 2);          //The String (2 characters) that identifies the measurement variable
            dataValue = variable.substring(startingIndex + 2, startingIndex + 2 + PACKAGE_DATA_VALUE_LENGTH);
            dataValueWithPrefix = parseParamValues(dataValue);                  //Parses the variable values and returns the actual values with their corresponding SI unit prefixes
            display.append(" ").append(String.format(Locale.getDefault(), "%1$ 2.1e", dataValueWithPrefix));
            listResponse.add(";" + variableIdentifier + ";" + String.format(Locale.getDefault(), "%1$1.6e", dataValueWithPrefix) );

            if (strMeasure.equals("LSV") || strMeasure.equals("CLV")) {
                if (variableIdentifier.equals("da")) potentials[4] = dataValueWithPrefix;
                if (variableIdentifier.equals("ba")) currents[4] = dataValueWithPrefix;
            }

            //If the variable is current this parses the metadata values.
            //The first character in each meta data value specifies the type of data.
            //1 - 1 char hex mask holding the status (0 = OK, 2 = overload, 4 = underload, 8 = overload warning (80% of max))
            //2 - 2 chars hex holding the current range index. First bit high (0x80) indicates a high speed mode cr.
            if (variableIdentifier.equals("ba") && variable.length() > 10 && variable.charAt(10) == ',') {
                String[] metaDataValues;
                metaDataValues = variable.split(",");
                for (String metaData : metaDataValues) {
                    switch (metaData.charAt(0)) {
                        case '1':
                            String status = getReadingStatus(metaData);
                            display.append(" ").append(String.format("%-5s", status));
                            listResponse.add(";" + status);
                            break;
                        case '2':
                            String range = getCurrentRange(metaData);
                            display.append(" ").append(range);
                            listResponse.add(";" + range);
                            break;
                        default:
                            break;
                    }
                }

            }
        }
        updateResponse(display.toString());
        listResponse.add("\n"); //This completes one line of data stored on the phone

        //If the measurement is LSV or CLV find the current peak.
        if (strMeasure.equals("LSV") || strMeasure.equals("CLV")) {
            if (currents[0] != 0) {
                double slope1 = currents[1] - currents[0];
                double slope2 = currents[2] - currents[1];
                double slope3 = currents[3] - currents[2];
                double slope4 = currents[4] - currents[3];
                if (potentials[0] > potentials[4]) {
                    if (slope1 < 0 && slope2 < 0 && slope3 > 0 && slope4 > 0) {
                        listPeak.add(( dataDate.format(new Date()) + ";" + strProcess + ";" + strMeasure + ";" + nMeasure + ";" + ( nDataPoints - 2 ) + ";" +
                                String.format(Locale.getDefault(), "%1$1.6e", potentials[2]) + ";" +
                                String.format(Locale.getDefault(), "%1$1.6e", currents[2]) + "\n" ));
                    }
                } else {
                    if (slope1 > 0 && slope2 > 0 && slope3 < 0 && slope4 < 0) {
                        listPeak.add(( dataDate.format(new Date()) + ";" + strProcess + ";" + strMeasure + ";" + nMeasure + ";" + ( nDataPoints - 2 ) + ";" +
                                String.format(Locale.getDefault(), "%1$1.6e", potentials[2]) + ";" +
                                String.format(Locale.getDefault(), "%1$1.6e", currents[2]) + "\n" ));
                    }
                }
            }
            potentials[0] = potentials[1];
            potentials[1] = potentials[2];
            potentials[2] = potentials[3];
            potentials[3] = potentials[4];
            currents[0] = currents[1];
            currents[1] = currents[2];
            currents[2] = currents[3];
            currents[3] = currents[4];
        }
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
     * Parses the reading status from the package. 1 char hex mask holding the status (0 = OK, 2 = overload, 4 = underload, 8 = overload warning (80% of max))
     * </Summary>
     *
     * @param metaStatus The status metadata to be parsed
     * @return the status as a string
     */
    private String getReadingStatus(String metaStatus) {
        switch (metaStatus.charAt(1)) {
            case '0':
                return "Ok";
            case '1':
                return "Tout";
            case '2':
                return "Over";
            case '4':
                return "Under";
            case '8':
                return ">80%";
            default:
                return "NaN";
        }
    }

    /**
     * <summary>
     * Displays the string corresponding to the input cr int
     * </summary>
     *
     * @param metaRange The status metadata to be parsed
     * @return the range as a string
     */
    private String getCurrentRange(String metaRange) {
        switch (( Integer.parseInt(metaRange.substring(1, 3), 16) )) {
            case 1:
                return "100nA";
            case 2:
                return "2uA";
            case 3:
                return "4uA";
            case 4:
                return "8uA";
            case 5:
                return "16uA";
            case 6:
                return "32uA";
            case 7:
                return "63uA";
            case 8:
                return "125uA";
            case 9:
                return "250uA";
            case 10:
                return "500uA";
            case 11:
                return "1mA";
            case 128:
                return "5mA";
            case 129:
                return "100nAhs";
            case 130:
                return "1uAhs";
            case 131:
                return "6uAhs";
            case 132:
                return "13uAhs";
            case 133:
                return "25uAhs";
            case 134:
                return "50uAhs";
            case 135:
                return "100uAhs";
            case 136:
                return "200uAhs";
            case 137:
                return "1mAhs";
            case 138:
                return "5mAhs";
            default:
                return metaRange.substring(1, 3);
        }
    }

    /**
     * <Summary>
     * Adds lines to the response list in the display and
     * limits the number of lines to 1024
     * </Summary>
     *
     * @param str the line to be added
     */
    private void updateResponse(String str) {
        listDisplay.add(str);
        int i = listDisplay.size();
        if (i > 1536) {
            listDisplay = new ArrayList<>(listDisplay.subList(i - 1024, i));
        }
        try {
            ArrayAdapter<String> listAdapter = new ArrayAdapter<>(this, R.layout.list_text_left, listDisplay);
            list.setAdapter(listAdapter);
            list.setSelection(listAdapter.getCount() - 1);
        } catch (Exception e) {
            Log.e(TAG, "refreshing" + e);
        }
    }

    /**
     * <summary>
     * Stores the string array listResponse to a file named Downloads/Data_YYYY_MM_DD_HH_MM_SS.csv
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
                            "/PalmData");
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
                String file = "Data_" + fileDate.format(new Date()) + ".csv";
                fileResponse = new File(fileFolder, file);
                file = "Peak_" + fileDate.format(new Date()) + ".csv";
                filePeak = new File(fileFolder, file);
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
            } catch (Exception e) {
                Log.w(TAG, "storing " + e);
                fileOk = false;
                Toast.makeText(this, "One measurement could not be stored", Toast.LENGTH_SHORT).show();
                abortScript();
            }
        } else {
            Toast.makeText(this, "No more data to store", Toast.LENGTH_SHORT).show();
        }

        if (!listPeak.isEmpty()) {
            try {
                FileOutputStream out = new FileOutputStream(filePeak, true);
                OutputStreamWriter osw = new OutputStreamWriter(out);
                for (String str : listPeak) osw.write(str);
                osw.flush();
                osw.close();
                listPeak.clear();
            } catch (Exception e) {
                Log.w(TAG, "storing " + e);
                abortScript();
            }
        }
    }

//region Events

    /**
     * <Summary>
     * Opens the device on click of connect and sends the version command to verify if the device is EmStat Pico.
     * </Summary>
     *
     */
    public void onClickConnect() {
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
     */
    public void onClickScripts() {
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
     */
    public void onClickAbort() {
        abortScript();
    }

    /**
     * <Summary>
     * Calls the method to send the MethodSCRIPT.
     * </Summary>
     *
     */
    public void onClickStart() {
        if (sendScript()) {
            setAppState(AppState.ScriptRunning);
        } else {
            setAppState(AppState.IdleConnected);
        }
    }
//endregion

}
