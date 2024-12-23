package com.example.sensorlogger;

import static android.content.ContentValues.TAG;
import static android.hardware.Sensor.TYPE_ACCELEROMETER;
import static android.hardware.Sensor.TYPE_ACCELEROMETER_LIMITED_AXES;
import static android.hardware.Sensor.TYPE_ACCELEROMETER_LIMITED_AXES_UNCALIBRATED;
import static android.hardware.Sensor.TYPE_ACCELEROMETER_UNCALIBRATED;
import static android.hardware.Sensor.TYPE_AMBIENT_TEMPERATURE;
import static android.hardware.Sensor.TYPE_GAME_ROTATION_VECTOR;
import static android.hardware.Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR;
import static android.hardware.Sensor.TYPE_GRAVITY;
import static android.hardware.Sensor.TYPE_GYROSCOPE;
import static android.hardware.Sensor.TYPE_GYROSCOPE_LIMITED_AXES;
import static android.hardware.Sensor.TYPE_GYROSCOPE_LIMITED_AXES_UNCALIBRATED;
import static android.hardware.Sensor.TYPE_GYROSCOPE_UNCALIBRATED;
import static android.hardware.Sensor.TYPE_HEADING;
import static android.hardware.Sensor.TYPE_HEAD_TRACKER;
import static android.hardware.Sensor.TYPE_HEART_BEAT;
import static android.hardware.Sensor.TYPE_HEART_RATE;
import static android.hardware.Sensor.TYPE_HINGE_ANGLE;
import static android.hardware.Sensor.TYPE_LIGHT;
import static android.hardware.Sensor.TYPE_LINEAR_ACCELERATION;
import static android.hardware.Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT;
import static android.hardware.Sensor.TYPE_MAGNETIC_FIELD;
import static android.hardware.Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED;
import static android.hardware.Sensor.TYPE_MOTION_DETECT;
import static android.hardware.Sensor.TYPE_ORIENTATION;
import static android.hardware.Sensor.TYPE_POSE_6DOF;
import static android.hardware.Sensor.TYPE_PRESSURE;
import static android.hardware.Sensor.TYPE_PROXIMITY;
import static android.hardware.Sensor.TYPE_RELATIVE_HUMIDITY;
import static android.hardware.Sensor.TYPE_ROTATION_VECTOR;
import static android.hardware.Sensor.TYPE_SIGNIFICANT_MOTION;
import static android.hardware.Sensor.TYPE_STATIONARY_DETECT;
import static android.hardware.Sensor.TYPE_STEP_COUNTER;
import static android.hardware.Sensor.TYPE_STEP_DETECTOR;
import static android.hardware.Sensor.TYPE_TEMPERATURE;

import android.app.Service;
import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.text.format.DateFormat;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

public class SensorService extends Service {

    private SensorManager sensorManager;
    private List<Sensor> selectedSensors = new ArrayList<>();
    private Handler handler;
    private Runnable dataSender;
    private SharedPreferences sharedPreferences;
    public JSONArray sensorDataJsonArray = new JSONArray();

    protected boolean isServiceStopped = false;

    private List<SensorEventListener> sensorEventListeners = new ArrayList<>();

    public void onDestroy() {
        for (SensorEventListener sensorEventListener : sensorEventListeners) {
            sensorManager.unregisterListener(sensorEventListener);
        }
        isServiceStopped = true;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sharedPreferences = getSharedPreferences("SensorAppConfig", MODE_PRIVATE);
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);

        if (sensorManager != null) {
            List<Sensor> availableSensors = sensorManager.getSensorList(Sensor.TYPE_ALL);
            int sensorCount = sharedPreferences.getInt("sensorCount", 0);
            for (int i = 0; i < sensorCount; i++) {
                int sensorId = sharedPreferences.getInt("sensorId" + i, -1);
                for (Sensor sensor : availableSensors) {
                    if (sensor.getType() == sensorId) {
                        selectedSensors.add(sensor);
                    }
                }
            }
        }

        Thread thread = new Thread(() -> {
            try {
                for (Sensor sensor : selectedSensors) {
                    registerSensorDataHandler();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        thread.start();

        postSensorDataHandler();
    }

    private void registerSensorDataHandler() throws JSONException {
        sharedPreferences = getSharedPreferences("SensorAppConfig", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();

        int sensorCount = sharedPreferences.getInt("sensorCount", 0);
        for (int i = 0; i < sensorCount; i++) {
            Sensor sensor = selectedSensors.get(i);
            //for (Sensor sensor : selectedSensors) {
            String sensorConfigString = sharedPreferences.getString("sensor_config_" + i,"");
            JSONObject sensorConfig = new JSONObject(sensorConfigString);

            if (sensorConfig.getBoolean("isRegistered")) {
                continue;
            }

            String uuid = sensorConfig.getString("uuid");
            //String uuid = sharedPreferences.getString("sensorId" + sensor.getId() + "uuid", "");
            JSONObject sensorRegistrationData = collectSensorRegistrationData(sensor, uuid);
            Log.i("SensorService", "Collected sensor registration data");

            PostSensorRegistrationData sensorRegistration = new PostSensorRegistrationData();
            sensorRegistration.setSetRegistrationData(sensor, sensorRegistrationData);
            sensorRegistration.run();

            if(sensorRegistration.getSuccess()) {
                sensorConfig.put("isRegistered", true);
                Log.i("SensorService", "Success on registration sensor " + uuid);
            } else {
                Log.e("SensorService", "Failed registration sensor " + uuid);
            }
            editor.putString("sensor_config_" + i, sensorConfig.toString());
            //}
        }

        editor.apply();
    }

    @NonNull
    private SensorEventListener createSensorEventListener(Sensor sensor) {
        SensorEventListener listener = new SensorEventListener() {
            boolean dataRead = false;
            @Override
            public void onSensorChanged(SensorEvent event) {
                try {
                    if (!dataRead && !isServiceStopped) {
                        sensorDataJsonArray.put(collectSensorData(event, sensor));
                        dataRead = true;
                        Log.i("SensorService", "Sensor data collected");
                        postSensorData(collectSensorData(event, sensor), sensor);
                    }
                } catch (JSONException e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
                // Not Implemented
            }
        };

        sensorEventListeners.add(listener);

        return listener;
    }

    private void postSensorDataHandler() {
        handler = new Handler();

        dataSender = () -> {
            for (Sensor sensor : selectedSensors) {
                if (isServiceStopped) {
                    continue;
                }
                sensorManager.registerListener(
                        createSensorEventListener(sensor),
                        sensor, SensorManager.SENSOR_DELAY_NORMAL
                );
            }
            handler.postDelayed(dataSender, sharedPreferences.getInt("interval", 5) * 1000L);
        };
        handler.postDelayed(dataSender, 1000);
    }

    private String getCurrentDateTime() {
        Calendar cal = Calendar.getInstance(getResources().getConfiguration().getLocales().get(0));
        cal.setTimeInMillis(System.currentTimeMillis());
        Log.i("SensorService", String.valueOf(System.currentTimeMillis()));
        return DateFormat.format("yyyy-MM-dd kk:mm:ss", cal).toString();
    }

    private String getDate(long time) {
        Calendar cal = Calendar.getInstance(getResources().getConfiguration().getLocales().get(0));

        long timeInMillis = (new Date()).getTime()
                + (time - System.nanoTime()) / 1000000L;

        cal.setTimeInMillis(timeInMillis);
        Log.i("SensorService", String.valueOf(timeInMillis));
        return DateFormat.format("yyyy-MM-dd kk:mm:ss", cal).toString();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        isServiceStopped = false;
        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    protected JSONObject collectSensorRegistrationData(Sensor sensor, String uuid) throws JSONException {
        JSONObject sensorRegistrationData = new JSONObject();
        sensorRegistrationData.put("deviceId", uuid);
        sensorRegistrationData.put("deviceName", sensor.getName());
        sensorRegistrationData.put("deviceType", "TestFoo");
        sensorRegistrationData.put("deviceGroup", Build.MANUFACTURER + " " + Build.PRODUCT);
        sensorRegistrationData.put("deviceParentGroup", Build.DEVICE);
        sensorRegistrationData.put("deviceDataTypes",deviceDataTypes(sensor));
        return sensorRegistrationData;
    }

    private JSONArray deviceDataTypes(Sensor sensor) throws JSONException {
        JSONArray dataArray = new JSONArray();

        switch(sensor.getType()) {
            case TYPE_ACCELEROMETER:
            case TYPE_GRAVITY:
            case TYPE_LINEAR_ACCELERATION:
                JSONObject dataX = new JSONObject();
                dataX.put("type", Constant.TYPE_X_AXE);
                dataX.put("description", "Acceleration X axes");
                dataX.put("unit", "m/s&sup2;");
                dataArray.put(dataX);

                JSONObject dataY = new JSONObject();
                dataY.put("type", Constant.TYPE_Y_AXE);
                dataY.put("description", "Acceleration y axes");
                dataY.put("unit", "m/s&sup2;");
                dataArray.put(dataY);

                JSONObject dataZ = new JSONObject();
                dataZ.put("type", Constant.TYPE_Z_AXE);
                dataZ.put("description", "Acceleration z axes");
                dataZ.put("unit", "m/s&sup2;");
                dataArray.put(dataZ);
                break;
            case TYPE_MAGNETIC_FIELD:
                JSONObject dataMx = new JSONObject();
                dataMx.put("type", "magnetic-field-x-axes");
                dataMx.put("description", "Magnetic field X axes");
                dataMx.put("unit", "&#181;T");
                dataArray.put(dataMx);

                JSONObject dataMy = new JSONObject();
                dataMy.put("type", "magnetic-field-y-axes");
                dataMy.put("description", "Magnetic field y axes");
                dataMy.put("unit", "&#181;T");
                dataArray.put(dataMy);

                JSONObject dataMz = new JSONObject();
                dataMz.put("type", "magnetic-field-z-axes");
                dataMz.put("description", "Magnetic field z axes");
                dataMz.put("unit", "&#181;T");
                dataArray.put(dataMz);
                break;
            case TYPE_GYROSCOPE:
                JSONObject dataRx = new JSONObject();
                dataRx.put("type", "rotation-x-axes");
                dataRx.put("description", "Rotation X axes");
                dataRx.put("unit", "rad/s");
                dataArray.put(dataRx);

                JSONObject dataRy = new JSONObject();
                dataRy.put("type", "rotation-y-axes");
                dataRy.put("description", "Rotation y axes");
                dataRy.put("unit", "rad/s");
                dataArray.put(dataRy);

                JSONObject dataRz = new JSONObject();
                dataRz.put("type", "rotation-z-axes");
                dataRz.put("description", "Rotation z axes");
                dataRz.put("unit", "rad/s");
                dataArray.put(dataRz);
                break;
            case TYPE_LIGHT:
                JSONObject data = new JSONObject();
                data.put("type", "light-ambient");
                data.put("description", "Ambient light");
                data.put("unit", "lx");
                dataArray.put(data);
                break;
            case TYPE_PRESSURE:
                JSONObject dataP = new JSONObject();
                dataP.put("type", "pressure-ambient");
                dataP.put("description", "Pressur light");
                dataP.put("unit", "hPa");
                dataArray.put(dataP);
                break;
            case TYPE_PROXIMITY:
                JSONObject dataProx = new JSONObject();
                dataProx.put("type", "proximity");
                dataProx.put("description", "Proximity");
                dataProx.put("unit", "cm");
                dataArray.put(dataProx);
                break;
            case TYPE_RELATIVE_HUMIDITY:
                JSONObject dataHr = new JSONObject();
                dataHr.put("type", "humidity-rel");
                dataHr.put("description", "Relative humidity");
                dataHr.put("unit", "%");
                dataArray.put(dataHr);
                break;
            case TYPE_AMBIENT_TEMPERATURE:
                JSONObject dataT = new JSONObject();
                dataT.put("type", "temperature-ambient");
                dataT.put("description", "Ambient temperature");
                dataT.put("unit", "&ordm;C");
                dataArray.put(dataT);
                break;
            case TYPE_ROTATION_VECTOR:
            case TYPE_TEMPERATURE:
            case TYPE_ORIENTATION:
            case TYPE_MAGNETIC_FIELD_UNCALIBRATED:
            case TYPE_GAME_ROTATION_VECTOR:
            case TYPE_GYROSCOPE_UNCALIBRATED:
            case TYPE_SIGNIFICANT_MOTION:
            case TYPE_STEP_DETECTOR:
            case TYPE_STEP_COUNTER:
            case TYPE_GEOMAGNETIC_ROTATION_VECTOR:
            case TYPE_HEART_RATE:
            case TYPE_POSE_6DOF:
            case TYPE_STATIONARY_DETECT:
            case TYPE_MOTION_DETECT:
            case TYPE_HEART_BEAT:
            case TYPE_LOW_LATENCY_OFFBODY_DETECT:
            case TYPE_ACCELEROMETER_UNCALIBRATED:
            case TYPE_HINGE_ANGLE:
            case TYPE_HEAD_TRACKER:
            case TYPE_ACCELEROMETER_LIMITED_AXES:
            case TYPE_GYROSCOPE_LIMITED_AXES:
            case TYPE_ACCELEROMETER_LIMITED_AXES_UNCALIBRATED:
            case TYPE_GYROSCOPE_LIMITED_AXES_UNCALIBRATED:
            case TYPE_HEADING:
                JSONObject dataNa = new JSONObject();
                dataNa.put("type", "na");
                dataNa.put("description", "Not available");
                dataNa.put("unit", "n.a.");
                dataArray.put(dataNa);
                break;
            default:
        }
        return dataArray;
    }

    protected JSONObject collectSensorData(SensorEvent event, Sensor sensor) throws JSONException {
        JSONObject sensorData = new JSONObject();

        int sensorId = sensor.getId();
        //String uuid = sharedPreferences.getString("sensorId" + sensor.getId() + "uuid", "");

        //String uuid = sharedPreferences.getString("sensor_config_" + sensor.getId() + "uuid", "");

        String sensorConfigString = sharedPreferences.getString("sensor_config_" + sensor.getId(),"");
        JSONObject sensorConfig = new JSONObject(sensorConfigString);

        String uuid = sensorConfig.getString("uuid");
        boolean isRegistered = sensorConfig.getBoolean("isRegistered");

        if (isRegistered) {
            sensorData.put("deviceId", uuid);
            sensorData.put("date", getCurrentDateTime());

            // Create the data array
            JSONArray dataArray = new JSONArray();

            if (sensor.getType() == TYPE_ACCELEROMETER
                    || sensor.getType() == TYPE_GRAVITY
                    || sensor.getType() == TYPE_LINEAR_ACCELERATION) {
                int acceleration_x_axes = sharedPreferences.getInt("sensor_" + uuid + Constant.TYPE_X_AXE, 0);
                int acceleration_y_axes = sharedPreferences.getInt("sensor_" + uuid + Constant.TYPE_Y_AXE, 0);
                int acceleration_z_axes = sharedPreferences.getInt("sensor_" + uuid + Constant.TYPE_Z_AXE, 0);

                // Add objects to the array
                JSONObject data1 = new JSONObject();
                data1.put("dataTypeId", acceleration_x_axes);
                data1.put("value", event.values[0]);
                dataArray.put(data1);

                JSONObject data2 = new JSONObject();
                data2.put("dataTypeId", acceleration_y_axes);
                data2.put("value", event.values[1]);
                dataArray.put(data2);

                JSONObject data3 = new JSONObject();
                data3.put("dataTypeId", acceleration_z_axes);
                data3.put("value", event.values[2]);
                dataArray.put(data3);
            }


            // Add the data array to the main JSON Object
            sensorData.put("data", dataArray);

            sensorDataJsonArray.put(sensorData);
        } else {
            Log.e("SensorLogger", "Can not post data of unregistered sensor device!");
        }

        return sensorData;
    }

    public class PostSensorRegistrationData implements Runnable {
        private volatile boolean success;
        protected JSONObject registrationData;
        protected Sensor sensor;

        public void setSetRegistrationData(Sensor sensorToRegister, JSONObject sensorRegistrationData) {
            registrationData = sensorRegistrationData;
            sensor = sensorToRegister;
        }

        private void deviceDataTypes(String jsonString) {

            try {
                JSONObject jsonObject = new JSONObject(jsonString);

                boolean success = jsonObject.getBoolean("success");

                if (success) {

                    SharedPreferences.Editor editor = sharedPreferences.edit();

                    JSONArray registeredDeviceDataTypes = jsonObject.getJSONArray("data");

                    if (registeredDeviceDataTypes != null) {
                        String deviceUuid = registrationData.getString("deviceId");

                        //JSONObject deviceDataType = null;
                        for (int i = 0; i < registeredDeviceDataTypes.length(); i++) {
                            JSONObject deviceDataType = (JSONObject) registeredDeviceDataTypes.get(i);
                            editor.putInt("sensor_" + deviceUuid + deviceDataType.getString("type"), deviceDataType.getInt("id"));
                        }
                        editor.apply();
                    }

                }

            } catch (JSONException e) {
                Log.e("SensorService", "unexpected JSON exception on register device");
            }
        }

        private String readStream(InputStream in) {
            BufferedReader reader = null;
            StringBuffer response = new StringBuffer();
            try {
                reader = new BufferedReader(new InputStreamReader(in));
                String line = "";
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
            return response.toString();
        }

        @Override
        public void run() {
            String tld = sharedPreferences.getString("tld", "");
            URL url = null;
            try {
                url = new URL(tld + Constant.API_REGISTER_DEVICE);
            } catch (MalformedURLException e) {
                throw new RuntimeException(e);
            }
            HttpURLConnection client = null;
            try {
                client = (HttpURLConnection) url.openConnection();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            try {
                client.setRequestMethod("POST");
            } catch (ProtocolException e) {
                throw new RuntimeException(e);
            }
            client.setRequestProperty("Content-Type", "application/json");
            client.setRequestProperty("Accept", "application/json");
            String username = sharedPreferences.getString("username", "");
            String password = sharedPreferences.getString("password", "");
            String plainAuthString = username+":"+password;
            byte[] byteAuthString = plainAuthString.getBytes(StandardCharsets.UTF_8);
            String encoded = Base64.encodeToString(byteAuthString, Base64.NO_WRAP);
            client.setRequestProperty("Authorization", "Basic "+encoded);
            client.setDoOutput(true);
            client.setDoInput(true);

            String regData = registrationData.toString();

            try {
                DataOutputStream os = new DataOutputStream(client.getOutputStream());
                os.writeBytes(regData);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            try {
                BufferedReader br = null;
                int responseCode = client.getResponseCode();

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    String responseString = readStream(client.getInputStream());

                    deviceDataTypes(responseString);



                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            success = true;
        }

        public boolean getSuccess() {
            return success;
        }
    }

    private void postSensorData(JSONObject data, Sensor sensor) {
        new Thread(() -> {
            try {
                String tld = sharedPreferences.getString("tld", "");
                URL url = new URL(tld + Constant.API_CREATE_LOG);
                HttpURLConnection client = (HttpURLConnection) url.openConnection();
                client.setRequestMethod("POST");
                client.setRequestProperty("Content-Type", "application/json");
                client.setRequestProperty("Accept", "application/json");

                String username = sharedPreferences.getString("username", "");
                String password = sharedPreferences.getString("password", "");
                String plainAuthString = username+":"+password;
                byte[] byteAuthString = plainAuthString.getBytes(StandardCharsets.UTF_8);
                String encoded = Base64.encodeToString(byteAuthString, Base64.NO_WRAP);
                client.setRequestProperty("Authorization", "Basic "+encoded);
                client.setDoOutput(true);
                client.setDoInput(true);

                DataOutputStream os = new DataOutputStream(client.getOutputStream());
                os.writeBytes(data.toString());

                try {
                    BufferedReader br = null;
                    int responseCode = client.getResponseCode();

                    if (responseCode == 200) {
                        br = new BufferedReader(new InputStreamReader(client.getInputStream()));
                        String strCurrentLine;
                        while ((strCurrentLine = br.readLine()) != null) {
                            System.out.println(strCurrentLine);
                        }
                        Log.i("SensorService", "POST Data Response Code: " + responseCode);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }
}