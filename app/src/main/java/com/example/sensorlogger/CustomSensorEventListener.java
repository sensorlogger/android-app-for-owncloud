package com.example.sensorlogger;

import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;

public interface CustomSensorEventListener {
    public void onTimeElapsed(SensorEvent event);
}
