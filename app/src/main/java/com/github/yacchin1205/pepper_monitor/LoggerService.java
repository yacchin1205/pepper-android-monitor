package com.github.yacchin1205.pepper_monitor;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.preference.Preference;
import android.preference.PreferenceManager;
import android.util.Log;

import com.aldebaran.qi.AnyObject;
import com.aldebaran.qi.Future;
import com.aldebaran.qi.QiCallback;
import com.aldebaran.qi.QiFunction;
import com.aldebaran.qi.Session;
import com.aldebaran.qi.sdk.QiContext;
import com.github.yacchin1205.naoqi_wrapper_sample.ALMemory;

import org.fluentd.logger.FluentLogger;
import org.fluentd.logger.FluentLoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

public class LoggerService extends Service implements SharedPreferences.OnSharedPreferenceChangeListener {

    public static final String TAG = "pepper-monitor";

    private Worker currentWorker = null;

    public LoggerService() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        PreferenceManager.getDefaultSharedPreferences(this).registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public synchronized void onDestroy() {
        PreferenceManager.getDefaultSharedPreferences(this).unregisterOnSharedPreferenceChangeListener(this);

        detachWorker();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        detachWorker();
        startWorker();
        return START_STICKY;
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String s) {
        Log.i(TAG, "SharedPreferences changed");
        detachWorker();
        startWorker();
    }

    private void startWorker() {
        QiContext context = QiContext.get(this);
        context.getSharedRequirements().getSessionRequirement().satisfy().then(new QiFunction<AnyObject, Session>() {
            @Override
            public Future<AnyObject> onResult(Session session) throws Exception {
                return session.service(ALMemory.MODULE_NAME);
            }
        }).then(new QiCallback<AnyObject>() {
            @Override
            public void onResult(AnyObject anyObject) throws Exception {
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(LoggerService.this);
                if(! prefs.getBoolean("enable_fluentd", false)) {
                    Log.i(TAG, "Fluentd disabled");
                }else {
                    Log.i(TAG, "Fluentd enabled");
                    attachWorker(prefs, new ALMemory(anyObject));
                }
            }
        });
    }

    private synchronized void attachWorker(SharedPreferences prefs, ALMemory memory) {
        if (currentWorker != null) {
            currentWorker.destroy();
            currentWorker = null;
        }

        currentWorker = new Worker(prefs, memory);
        (new Thread(currentWorker)).start();
    }

    private synchronized void detachWorker() {
        if (currentWorker != null) {
            currentWorker.destroy();
            currentWorker = null;
        }
    }

    private class Worker implements Runnable {

        private final String[] ACTUATORS = new String[]{"HeadPitch", "HeadYaw",
                "RShoulderRoll", "RShoulderPitch", "RElbowYaw", "RElbowRoll",
                "RWristYaw", "RHand",
                "LShoulderRoll", "LShoulderPitch", "LElbowYaw", "LElbowRoll",
                "LWristYaw", "LHand",
                "HipPitch", "HipRoll", "KneePitch",
                "WheelFL", "WheelFR", "WheelB"};

        private boolean alive = true;

        private FluentLogger logger;

        private int intervalSec;

        private ALMemory memory;

        public Worker(SharedPreferences prefs, ALMemory memory) {
            this.memory = memory;
            String tagPrefix = prefs.getString("tag_prefix", "pepper");
            String host = prefs.getString("fluentd_host", "127.0.0.1");
            int port = Integer.parseInt(prefs.getString("fluentd_port", "24224"));
            this.logger = FluentLogger.getLogger(tagPrefix, host, port);

            this.intervalSec = Math.max(1, Integer.parseInt(prefs.getString("interval_sec", "60")));
        }

        public synchronized void destroy() {
            alive = false;
        }

        @Override
        public void run() {
            Log.i(TAG, "Service started");
            while (isAlive()) {
                try {
                    Log.d(TAG, "Sending events...");
                    logger.log("battery", getBatteryStatus());
                    logger.log("temperature", getTemperatureStatus());
                } catch (Throwable th) {
                    Log.e(TAG, "Retrieve error", th);
                }
                try {
                    Thread.sleep(intervalSec * 1000);
                } catch (InterruptedException e) {
                    Log.e(TAG, "Error", e);
                }
            }
            Log.i(TAG, "Service stopped");
        }

        private Map<String, Object> getBatteryStatus() throws ExecutionException {
            HashMap<String, Object> values = new HashMap<String, Object>();
            values.put("charge", toNumber(memory.getData("BatteryChargeChanged").get()));
            return values;
        }

        private Map<String, Object> getTemperatureStatus() throws ExecutionException {
            HashMap<String, Object> values = new HashMap<String, Object>();
            for (String actuator : ACTUATORS) {
                String key = String.format("Device/SubDeviceList/%s/Temperature/Sensor/Value", actuator);
                values.put(actuator.toLowerCase(), toNumber(memory.getData(key).get()));
            }
            return values;
        }

        private boolean isAlive() {
            return alive;
        }

        private Float toNumber(Object v) {
            if(v instanceof Void) {
                return null;
            }
            return Float.parseFloat(v.toString());
        }

    }
}
