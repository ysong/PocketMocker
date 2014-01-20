package edu.buffalo.cse.blue.recordreplay;

import android.app.ActionBar;
import android.app.Activity;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.widget.TextView;
import edu.buffalo.cse.blue.recordreplay.models.DatabaseHandler;

public class MainActivity extends Activity {

	private String TAG = "REC";

	private RecordReplayApplication app;

	private TextView locationText;
	private String locationPrefix;
	private String activePathId;

	private LocationManager locationManager;

	private ObjectiveFragment objectiveFragment = new ObjectiveFragment();
	private RecordFragment recordFragment = new RecordFragment();
	private LoadFragment loadFragment = new LoadFragment();

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
	
		app = (RecordReplayApplication) this.getApplicationContext();	

		ActionBar actionBar = this.getActionBar();
		actionBar.setDisplayShowHomeEnabled(false);
		actionBar.setDisplayShowTitleEnabled(false);
		actionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_TABS);

		ActionBar.Tab pathTab, loadTab, recordTab;
		pathTab = actionBar.newTab().setText(
				this.getString(R.string.objective_tab));
		pathTab.setTabListener(new TabListener(objectiveFragment));
		loadTab = actionBar.newTab().setText(
				this.getString(R.string.load_paths_tab));
		loadTab.setTabListener(new TabListener(loadFragment));
		recordTab = actionBar.newTab().setText(
				this.getString(R.string.record_path_tab));
		recordTab.setTabListener(new TabListener(recordFragment));

		actionBar.addTab(pathTab);
		actionBar.addTab(loadTab);
		actionBar.addTab(recordTab);

		locationPrefix = this.getString(R.string.loc_prefix);

		locationManager = (LocationManager) this
				.getSystemService(Context.LOCATION_SERVICE);
		locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0,
				0, new LocationListener() {

					@Override
					public void onLocationChanged(Location loc) {
						Log.v(TAG,
								"LocationChange, recording: "
										+ app.isRecording());
						if (app.isRecording()) {
							// Logging only when recording because otherwise
							// it's a huge mess in LogCat
							Log.v(TAG,
									"LocatoinChanged. Loc: " + loc.toString());
							// Do we need to log every location change?
							app.getDatabase().insertLocation(loc, activePathId);
							String displayLoc = MainActivity
									.buildLocationDisplayString(loc);
							locationText.setText(locationPrefix + displayLoc);
							Log.v(TAG,
									"Location count: " + app.getDatabase().getLocationCount());
						}
					}

					@Override
					public void onProviderDisabled(String arg0) {
						Log.v(TAG,
								"Provider disabled. Alert user that we need this turned on to function.");
					}

					@Override
					public void onProviderEnabled(String arg0) {
						Log.v(TAG, "Provider enabled. Woot, we can log things.");
					}

					@Override
					public void onStatusChanged(String arg0, int arg1,
							Bundle arg2) {
						Log.v(TAG, "onStatusChanged. Nothing to do here yet.");
					}

				});
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.main, menu);
		return true;
	}

	public static String buildLocationDisplayString(Location loc) {
		return "(" + loc.getLatitude() + ", " + loc.getLongitude() + ")";
	}
	
	public RecordReplayApplication getApp() {
		return app;
	}

	public String getActivePathId() {
		return activePathId;
	}

	public TextView getLocationText() {
		return locationText;
	}

	public String getLocationPrefix() {
		return locationPrefix;
	}

	public LocationManager getLocationManager() {
		return locationManager;
	}

	// This is stupid.
	public void setLocationText(TextView view) {
		locationText = view;
	}

}
