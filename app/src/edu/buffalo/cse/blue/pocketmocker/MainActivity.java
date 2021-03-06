
package edu.buffalo.cse.blue.pocketmocker;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.telephony.TelephonyManager;
import android.telephony.cdma.CdmaCellLocation;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import edu.buffalo.cse.blue.pocketmocker.models.MockCellLocationManager;
import edu.buffalo.cse.blue.pocketmocker.models.MockLocationManager;
import edu.buffalo.cse.blue.pocketmocker.models.MockSensorEventManager;
import edu.buffalo.cse.blue.pocketmocker.models.MockWifiManager;
import edu.buffalo.cse.blue.pocketmocker.models.Objective;
import edu.buffalo.cse.blue.pocketmocker.models.ObjectivesManager;
import edu.buffalo.cse.blue.pocketmocker.models.RecordReplayManager;
import edu.buffalo.cse.blue.pocketmocker.models.Recording;
import edu.buffalo.cse.blue.pocketmocker.models.RecordingManager;

import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Random;

public class MainActivity extends Activity {

    public static final String TAG = "PM";

    private PocketMockerApplication app;

    private TextView mLog;
    private Button recordButton;

    private Spinner objectivesSpinner;
    private boolean spinnerInitFlag;

    private ObjectivesManager objectivesManager;
    private RecordingManager recordingManager;
    private MockLocationManager mockLocationManager;
    private RecordReplayManager recordReplayManager;
    private MockSensorEventManager mockSensorEventManager;
    private MockWifiManager mockWifiManager;
    private MockCellLocationManager mockCellLocationManager;

    private LocationManager locationManager;
    private HandlerThread locationHandlerThread;
    private LocationListener locationListener;
    // We use lastLocation as the location to add when we have other updates
    // that don't receive a Location as a parameter in the callback
    private Location lastLocation;

    private SensorManager sensorManager;
    private HandlerThread sensorHandlerThread;
    private SensorEventListener sensorEventListener;

    private WifiManager mWifiManager;
    private TelephonyManager telephonyManager;

    private Random random;

    private Messenger mMockerMessenger;
    private ServiceConnection mMockerServiceConnection;
    private Messenger mIncomingMessenger;

    class ReplayStateHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            Bundle data = msg.getData();
            if (data.containsKey(MockerService.IS_REPLAYING)) {
                boolean isReplaying = data.getBoolean(MockerService.IS_REPLAYING);
                recordReplayManager.setIsReplaying(isReplaying);
                app.setIsReplaying(isReplaying);
                Button replayButton = (Button) findViewById(R.id.replay_button);
                // We can only stop replaying from the service
                replayButton.setText(R.string.replay);
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        app = (PocketMockerApplication) getApplicationContext();
        random = new Random();
        mLog = (TextView) findViewById(R.id.log);

        objectivesManager = ObjectivesManager.getInstance(getApplicationContext());
        recordingManager = RecordingManager.getInstance(getApplicationContext());
        mockLocationManager = MockLocationManager.getInstance(getApplicationContext());
        recordReplayManager = RecordReplayManager.getInstance(getApplicationContext());
        mockSensorEventManager = MockSensorEventManager.getInstance(getApplicationContext());
        mockWifiManager = MockWifiManager.getInstance(getApplicationContext());
        mockCellLocationManager = MockCellLocationManager.getInstance(getApplicationContext());
        recordReplayManager.setIsRecording(false);
        app.setIsRecording(false);

        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.SUPPLICANT_CONNECTION_CHANGE_ACTION);
        filter.addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        HandlerThread wifiReceiverThread = new HandlerThread("WifiReceiverThread");
        wifiReceiverThread.start();
        Handler wifiReceiverHandler = new Handler(wifiReceiverThread.getLooper());
        BroadcastReceiver wifiReceiver = new WifiManagerReceiver(getApplicationContext());
        this.registerReceiver(wifiReceiver, filter, "", wifiReceiverHandler);

        this.checkFirstTimeUse();

        spinnerInitFlag = false;
        objectivesSpinner = (Spinner) this.findViewById(R.id.objectives_spinner);
        objectivesSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {

            @Override
            public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                if (!spinnerInitFlag) {
                    // Workaround for when this gets called when the
                    // view is initially rendered
                    spinnerInitFlag = true;
                } else {
                    recordReplayManager.setIsRecording(false);
                    app.setIsRecording(false);
                    toggleRecordingButton();
                    String selectedText = objectivesSpinner.getSelectedItem().toString();
                    if (selectedText.equals(objectivesManager.getMockObjectiveString())) {
                        displayNewObjectiveDialog();
                    }
                    Log.v(TAG, "Selected: " + objectivesSpinner.getSelectedItem().toString());
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {
                Log.v(TAG, "Spinner nothing.");
            }
        });
        this.populateObjectivesSpinner();
        List<Objective> objectives = objectivesManager.getObjectives();
        if (objectives.size() > 1) {
            Log.v(TAG, "Setting current rec id to: " + objectives.get(0).getRecordingId());
            recordingManager.setCurrentRecordingId(objectives.get(0).getRecordingId());
        }

        recordButton = (Button) this.findViewById(R.id.record_button);

        initLocationManager();
        initSensorManager();
        mWifiManager = (WifiManager) this.getSystemService(Context.WIFI_SERVICE);
        telephonyManager = (TelephonyManager) this.getSystemService(Context.TELEPHONY_SERVICE);
        initMockerServiceMessenger();

        new Thread(new SubscribeTask()).start();
    }

    private class SubscribeTask implements Runnable {
        @Override
        public void run() {
            Message subMsg = Message.obtain();
            Bundle data = new Bundle();
            data.putString(MockerService.PACKAGE, getPackageName());
            data.putInt(MockerService.PM_ACTION_KEY, MockerService.PM_ACTION_SUB);
            subMsg.replyTo = mIncomingMessenger;
            subMsg.setData(data);
            try {
                while (mMockerMessenger == null) {
                } // it takes a while to connect to the service
                mMockerMessenger.send(subMsg);
            } catch (RemoteException e) {
                Log.v(TAG, "Unable to send sub message to MockerService!");
            }
            Log.v(TAG, "SENT MSG");
        }
    }

    private void initMockerServiceMessenger() {
        mMockerServiceConnection = new ServiceConnection() {

            @Override
            public void onServiceConnected(ComponentName className, IBinder service) {
                mMockerMessenger = new Messenger(service);
                Log.v(TAG, "mMockerMessenger: " + mMockerMessenger);
            }

            @Override
            public void onServiceDisconnected(ComponentName className) {
                doBindService();
            }

        };
        doBindService();
        mIncomingMessenger = new Messenger(new ReplayStateHandler());
    }

    private void doBindService() {
        this.bindService(new Intent(this, MockerService.class), mMockerServiceConnection,
                Context.BIND_AUTO_CREATE);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_about:
                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setMessage(R.string.about_detail);
                builder.create().show();
                return true;
            case R.id.action_settings:
                Intent intent = new Intent(this, SettingsActivity.class);
                startActivity(intent);
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void updateLog(final String s) {
        final ScrollView sv = (ScrollView) findViewById(R.id.log_scrollview);
        mLog.post(new Runnable() {
            @Override
            public void run() {
                mLog.append(s + "\n");
            }
        });
        sv.post(new Runnable() {
            @Override
            public void run() {
                sv.fullScroll(ScrollView.FOCUS_DOWN);
            }
        });
    }

    private void initSensorManager() {
        // Starts when the recording process begins
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        sensorHandlerThread = new HandlerThread("SensorHandlerThread");
        sensorHandlerThread.start();
        sensorEventListener = new SensorEventListener() {

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
                // Don't record sensor data until we have location data (for
                // now)
                if (lastLocation != null) {
                    Log.v(TAG, "Accuracy change: " + accuracy);
                    mockSensorEventManager.addAccuracyChange(sensor, accuracy);
                    updateLog(sensor.getName() + " sensor accuracy changed: " + accuracy);
                }
            }

            @Override
            public void onSensorChanged(SensorEvent event) {
                // Don't record sensor data until we have location data (for
                // now)
                if (lastLocation != null) {
                    Log.v(TAG, "Sensor changed: " + Arrays.toString(event.values));
                    mockSensorEventManager.addSensorEvent(event);
                    // Only going to record a 1/4 of all sensor updates because
                    // there's A LOT
                    if (random.nextInt(8) == 0) {
                        updateLog(event.sensor.getName() + " sensor event");
                    }
                }
            }
        };
    }

    public void startSensorListener() {
        Log.v(TAG, "Listening for sensor updates.");
        for (Sensor s : sensorManager.getSensorList(Sensor.TYPE_ALL)) {
            sensorManager.registerListener(sensorEventListener,
                    s, SensorManager.SENSOR_DELAY_GAME,
                    new Handler(sensorHandlerThread.getLooper()));
        }
    }

    public void stopSensorListener() {
        Log.v(TAG, "Stopping listening for sensor updates.");
        updateLog("Stopping sensor listeners.");
        // Unregister our listener for all sensors
        sensorManager.unregisterListener(sensorEventListener);
    }

    private void initLocationManager() {
        locationManager = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);
        locationHandlerThread = new HandlerThread("LocationHandlerThread");
        locationHandlerThread.start();
        locationListener = new LocationListener() {

            @Override
            public void onLocationChanged(Location loc) {
                lastLocation = loc;
                mockLocationManager.addLocation(loc, "onLocationChanged", -1);
                final String displayLoc = app.buildLocationDisplayString(loc);
                updateLog("Recorded: " + displayLoc);
                new Thread(new WifiScanResultTask(mockWifiManager, mWifiManager, mLog)).start();
                // Since we're only on GNexus on Sprint, this is okay
                CdmaCellLocation cellLoc = (CdmaCellLocation)telephonyManager.getCellLocation();
                mockCellLocationManager.addCellLocation(cellLoc);
                updateLog("CellLocation: " + cellLoc.getBaseStationId());
            }

            @Override
            public void onProviderDisabled(String provider) {
                Log.v(TAG,
                        "Provider disabled. Alert user that we need this turned on to function.");
                lastLocation.setProvider(provider);
                mockLocationManager.addLocation(lastLocation, "onProviderDisabled", -1);
            }

            @Override
            public void onProviderEnabled(String provider) {
                Log.v(TAG, "Provider enabled. Woot, we can log things.");
                lastLocation.setProvider(provider);
                mockLocationManager.addLocation(lastLocation, "onProviderEnabled", -1);
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {
                Log.v(TAG, "onStatusChanged. Nothing to do here yet.");
                lastLocation.setExtras(extras);
                lastLocation.setProvider(provider);
                mockLocationManager.addLocation(lastLocation, "onStatusChanged", status);
            }

        };
    }

    public void startLocationListener() {
        Log.v(TAG, "Listening for location updates.");
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, -1,
                locationListener, locationHandlerThread.getLooper());
    }

    public void stopLocationListener() {
        Log.v(TAG, "Stopping listening for location updates.");
        locationManager.removeUpdates(locationListener);
    }

    private void displayNewObjectiveDialog() {
        NewObjectiveDialog dialog = new NewObjectiveDialog();
        dialog.show(getFragmentManager(), TAG);
    }

    private void checkFirstTimeUse() {
        // No existing objectives besides the mock, so we can assume it's the
        // first time the user is using the app.
        if (objectivesManager.getObjectives().size() == 1) {
            NewObjectiveDialog dialog = new NewObjectiveDialog();
            Bundle b = new Bundle();
            b.putBoolean(NewObjectiveDialog.FIRST_KEY, true);
            dialog.setArguments(b);
            dialog.show(getFragmentManager(), TAG);
        }
    }

    public void populateObjectivesSpinner() {
        ArrayAdapter<String> objectivesSpinnerAdapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, objectivesManager.getObjectivesNames());
        objectivesSpinnerAdapter
                .setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        objectivesSpinner.setAdapter(objectivesSpinnerAdapter);
    }

    public String getSelectedObjectiveName() {
        return objectivesSpinner.getSelectedItem().toString();
    }

    public void toggleRecordingButton() {
        if (app.isRecording()) {
            recordButton.setText(R.string.stop_record);
            startSensorListener();
            startLocationListener();
        } else {
            recordButton.setText(R.string.record);
            stopSensorListener();
            stopLocationListener();
        }
        lastLocation = null;
        Log.v(TAG, "Recording: " + app.isRecording());
    }

    private void prepareToRecord() {
        toggleRecordingButton();
        if (app.isRecording()) {
            Location lastLoc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (lastLoc == null) {
                updateLog("Waiting for location...");
                // updateLocationText("Waiting for location...");
            } else {
                lastLocation = lastLoc;
                mockLocationManager.addLocation(lastLoc, "onLocationChanged", -1);
                updateLog(app.buildLocationDisplayString(lastLoc));
                // updateLocationText(lastLoc);
            }
        } else {
            // resetLocationText();
            // updateSensorText("Not listenting for sensor updates.");
        }
    }

    public void recordButtonClicked(View v) {
        if (!recordReplayManager.isReplaying()) {
            Log.v(MainActivity.TAG, "Checking if objective (" + getSelectedObjectiveName()
                    + ") already has a recording.");
            if (!app.isRecording()) {
                if (objectivesManager.hasMocks(getSelectedObjectiveName())) {
                    showOverwriteRecordingDialog();
                } else {
                    recordReplayManager.toggleRecording();
                    app.toggleIsRecording();
                }
            } else {
                recordReplayManager.setIsRecording(false);
                app.setIsRecording(false);
            }
            prepareToRecord();
            // also acts as a reset when we're not recording
            mockWifiManager.enableGrouping();
        } else {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setMessage(R.string.no_record_while_replay);
            builder.create().show();
        }
    }

    public void replayButtonClicked(View v) {
        if (!recordReplayManager.isRecording()) {
            Message m = Message.obtain();
            m.replyTo = this.mIncomingMessenger;
            Bundle b = new Bundle();
            b.putString(MockerService.PACKAGE, this.getPackageName());
            Button replayButton = (Button) v;
            Log.v(TAG, "replayButton: " + replayButton.getText());
            if (replayButton.getText().equals(this.getString(R.string.replay))) {
                Log.v(TAG, "Stop replaying.");
                replayButton.setText(R.string.stop_replaying);
                b.putInt(MockerService.PM_ACTION_KEY, MockerService.PM_ACTION_START_REPLAY);
            } else {
                Log.v(TAG, "Start replaying.");
                replayButton.setText(R.string.replay);
                b.putInt(MockerService.PM_ACTION_KEY, MockerService.PM_ACTION_STOP_REPLAY);
            }
            m.setData(b);
            try {
                mMockerMessenger.send(m);
            } catch (RemoteException e) {
                Log.v(TAG, "RemoteException", e);
                e.printStackTrace();
            }
            recordReplayManager.toggleReplaying();
        } else {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setMessage(R.string.no_replay_while_record);
            builder.create().show();
        }
    }

    public void showOverwriteRecordingDialog() {
        OverwriteRecordingDialog dialog = new OverwriteRecordingDialog();
        Bundle b = new Bundle();
        b.putString("objective", this.getSelectedObjectiveName());
        dialog.setArguments(b);
        dialog.show(this.getFragmentManager(), TAG);
    }

    public void overwriteRecording() {
        long recId = recordingManager.addRecording(new Recording());
        Objective o = objectivesManager.getObjectiveByName(this.getSelectedObjectiveName());
        o.setRecordingId(recId);
        o.setLastModifiedDate(new Date());
        objectivesManager.updateObjective(o);
        recordingManager.setCurrentRecordingId(recId);
        recordReplayManager.setIsRecording(true);
        app.setIsRecording(true);
        this.toggleRecordingButton();
    }

}
