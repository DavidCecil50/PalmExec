package com.obdzero.palmexec;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Helper class to communicate with Shelly Gen 3 devices
 */
public class ShellyDevice {
    private final String ipAddress;
    private static final int TIMEOUT = 10000; // 10 seconds

    public ShellyDevice(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    /**
     * Turns the Shelly device on
     *
     */
    public String turnOn() {
        return setSwitchState("true");
    }

    /**
     * Turns the Shelly device off
     *
     */
    public String turnOff() {
        return setSwitchState("false");
    }

    /**
     * Sets the switch state
     *
     * @param state true for on, false for off
     * Posted by BenjaminFB
     * Retrieved 2026-05-17, License - CC BY-SA 4.0
     */

    private String setSwitchState(String state) {
        try {
            URL urlGetRequest = new URL("http://" + ipAddress + "/rpc/Switch.Set?id=0&on=" + state);
            HttpURLConnection connection = (HttpURLConnection) urlGetRequest.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(TIMEOUT);
            connection.setReadTimeout(TIMEOUT);

            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream())
                );
                StringBuilder response = new StringBuilder();
                String line;
                while (( line = reader.readLine() ) != null) {
                    response.append(line);
                }
                reader.close();
                connection.disconnect();
                return response.toString();
            } else {
                connection.disconnect();
                return String.valueOf(responseCode);
            }
        } catch (Exception e) {
            return String.valueOf(e);
        }
    }

    /**
     * Gets the current state of the switch
     */

    public String getSwitchStatus() {
        try {
            URL urlGetRequest = new URL("http://" + ipAddress + "/rpc/Switch.GetStatus?id=0");

            HttpURLConnection connection = (HttpURLConnection) urlGetRequest.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(TIMEOUT);
            connection.setReadTimeout(TIMEOUT);


            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream())
                );
                StringBuilder response = new StringBuilder();
                String line;
                while (( line = reader.readLine() ) != null) {
                    response.append(line);
                }
                reader.close();
                connection.disconnect();
                return response.toString();
            }

            connection.disconnect();
            return String.valueOf(responseCode);

        } catch (Exception e) {
            return String.valueOf(e);
        }
    }
}