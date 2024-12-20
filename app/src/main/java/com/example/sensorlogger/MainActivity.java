package com.example.sensorlogger;


import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private SensorManager sensorManager;
    private List<Sensor> availableSensors = new ArrayList<>();
    private List<Sensor> selectedSensors = new ArrayList<>();
    private SharedPreferences sharedPreferences;

    private EditText tldInput, usernameInput, passwordInput, intervalInput;
    private LinearLayout sensorCheckboxContainer;
    private Button saveButton, startServiceButton, removeButton;
    private Handler handler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        sharedPreferences = getSharedPreferences("SensorAppConfig", Context.MODE_PRIVATE);
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);

        tldInput = findViewById(R.id.tldInput);
        usernameInput = findViewById(R.id.usernameInput);
        passwordInput = findViewById(R.id.passwordInput);
        intervalInput = findViewById(R.id.intervalInput);
        sensorCheckboxContainer = findViewById(R.id.sensorCheckboxContainer);
        saveButton = findViewById(R.id.saveButton);
        removeButton = findViewById(R.id.removeButton);
        startServiceButton = findViewById(R.id.startServiceButton);

        handler = new Handler();

        if (sensorManager != null) {
            availableSensors = sensorManager.getSensorList(Sensor.TYPE_ALL);
            populateSensorCheckboxes();
        }

        saveButton.setOnClickListener(v -> {
            try {
                saveConfig();
            } catch (JSONException e) {
                throw new RuntimeException(e);
            }
        });
        removeButton.setOnClickListener(v -> removeSensorConfig());
        startServiceButton.setOnClickListener(v -> startBackgroundService());

        loadConfig();
    }

    private void populateSensorCheckboxes() {
        for (Sensor sensor : availableSensors) {
            CheckBox checkBox = new CheckBox(this);
            checkBox.setText(sensor.getName());
            checkBox.setTag(sensor);
            sensorCheckboxContainer.addView(checkBox);
        }
    }

    private void removeSensorConfig() {
        SharedPreferences.Editor editor = sharedPreferences.edit();

        for (int i = 0; i < availableSensors.size(); i++) {
            //editor.putString("sensorIdentifier" + i, UUID.randomUUID().toString());
            //editor.putInt("sensorId" + i, selectedSensors.get(i).getType());
            editor.remove("sensorId" + i);
            editor.remove("sensorId" + i + "uuid");
            editor.remove("sensorConfigId" + i);
            editor.remove("sensorIdentifier" + i);
            editor.putInt("sensorCount", 0);
            editor.remove("aSensorId" + i);
            editor.remove("rSensorId" + i);
            editor.remove("sensor_config_" + i);
        }

        for (String key : sharedPreferences.getAll().keySet()) {
            if (key.startsWith("sensor_")) {
                editor.remove(key);
            }
        }
        editor.apply();
        Toast.makeText(this, "Sensor Configurations deleted!", Toast.LENGTH_SHORT).show();
    }

    private void saveConfig() throws JSONException {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString("tld", tldInput.getText().toString());
        editor.putString("username", usernameInput.getText().toString());
        editor.putString("password", passwordInput.getText().toString());
        editor.putInt("interval", Integer.parseInt(intervalInput.getText().toString()));

        selectedSensors.clear();
        for (int i = 0; i < sensorCheckboxContainer.getChildCount(); i++) {
            CheckBox checkBox = (CheckBox) sensorCheckboxContainer.getChildAt(i);
            if (checkBox.isChecked()) {
                selectedSensors.add((Sensor) checkBox.getTag());
            }
        }
        editor.putInt("sensorCount", selectedSensors.size());
        for (int i = 0; i < selectedSensors.size(); i++) {
            //editor.putString("sensorIdentifier" + i, UUID.randomUUID().toString());
            editor.putInt("sensorId" + i, selectedSensors.get(i).getType());
            editor.putString("sensorId" + i +"uuid", UUID.randomUUID().toString());

            if (!sharedPreferences.contains("sensor_config_" + i)) {
                JSONObject sensorConfig = new JSONObject();
                sensorConfig.put("uuid", UUID.randomUUID().toString());
                sensorConfig.put("isRegistered", false);
                sensorConfig.put("type", selectedSensors.get(i).getType());
                editor.putString("sensor_config_" + i, sensorConfig.toString());
            }
        }

        editor.apply();
        Toast.makeText(this, "Configuration saved", Toast.LENGTH_SHORT).show();
    }

    private void loadConfig() {
        tldInput.setText(sharedPreferences.getString("tld", ""));
        usernameInput.setText(sharedPreferences.getString("username", ""));
        passwordInput.setText(sharedPreferences.getString("password", ""));
        intervalInput.setText(String.valueOf(sharedPreferences.getInt("interval", 5)));

        int sensorCount = sharedPreferences.getInt("sensorCount", 0);
        for (int i = 0; i < sensorCount; i++) {
            int sensorId = sharedPreferences.getInt("sensorId" + i, -1);
            for (Sensor sensor : availableSensors) {
                if (sensor.getType() == sensorId && !sensor.isWakeUpSensor()) {
                    selectedSensors.add(sensor);
                }
            }
        }

        for (int i = 0; i < sensorCheckboxContainer.getChildCount(); i++) {
            CheckBox checkBox = (CheckBox) sensorCheckboxContainer.getChildAt(i);
            Sensor sensor = (Sensor) checkBox.getTag();
            checkBox.setChecked(selectedSensors.contains(sensor));
        }
    }

    private void startBackgroundService() {
        Intent intent = new Intent(this, SensorService.class);
        stopService(intent);
        startService(intent);

        Toast.makeText(this, "Service started", Toast.LENGTH_SHORT).show();
    }
}