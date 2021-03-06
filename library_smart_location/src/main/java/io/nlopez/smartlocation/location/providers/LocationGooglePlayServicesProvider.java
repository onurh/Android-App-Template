package io.nlopez.smartlocation.location.providers;

import android.app.Activity;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Bundle;
import android.support.annotation.NonNull;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.LocationSettingsRequest;
import com.google.android.gms.location.LocationSettingsResult;
import com.google.android.gms.location.LocationSettingsStatusCodes;

import io.nlopez.smartlocation.location.LocationProvider;
import io.nlopez.smartlocation.location.LocationStore;
import io.nlopez.smartlocation.utils.GooglePlayServicesListener;

/**
 * Created by mrm on 20/12/14.
 */
public class LocationGooglePlayServicesProvider extends LocationProvider implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener, LocationListener, ResultCallback<Status> {
    private static final String GMS_ID = "GMS";
    private final GooglePlayServicesListener googlePlayServicesListener;
    private GoogleApiClient client;
    private boolean shouldStart = false;
    private boolean stopped = false;
    private LocationStore locationStore;
    private LocationRequest locationRequest;
    private boolean checkLocationSettings;
    private boolean fulfilledCheckLocationSettings;
    private ResultCallback<LocationSettingsResult> settingsResultCallback = new ResultCallback<LocationSettingsResult>() {
        @Override
        public void onResult(LocationSettingsResult locationSettingsResult) {
            final Status status = locationSettingsResult.getStatus();
            switch (status.getStatusCode()) {
                case LocationSettingsStatusCodes.SUCCESS:
                    logger.d("All location settings are satisfied.");
                    fulfilledCheckLocationSettings = true;
                    startUpdating(locationRequest);
                    break;
                case LocationSettingsStatusCodes.RESOLUTION_REQUIRED:
                    logger.w("Location settings are not satisfied. Show the user a dialog to" +
                            "upgrade location settings. You should hook into the Activity onActivityResult and call this provider onActivityResult method for continuing this call flow. ");

                    if (context instanceof Activity) {
                        try {
                            // Show the dialog by calling startResolutionForResult(), and check the result
                            // in onActivityResult().
                            status.startResolutionForResult((Activity) context, REQUEST_CHECK_SETTINGS);
                        } catch (IntentSender.SendIntentException e) {
                            logger.i("PendingIntent unable to execute request.");
                        }

                    } else {
                        logger.w("Provided context is not the context of an activity, therefore we cant launch the resolution activity.");
                    }
                    break;
                case LocationSettingsStatusCodes.SETTINGS_CHANGE_UNAVAILABLE:
                    logger.i("Location settings are inadequate, and cannot be fixed here. Dialog " +
                            "not created.");
                    stop();
                    break;
            }
        }
    };

    public LocationGooglePlayServicesProvider() {
        this(null);
    }

    public LocationGooglePlayServicesProvider(GooglePlayServicesListener playServicesListener) {
        googlePlayServicesListener = playServicesListener;
        checkLocationSettings = false;
        fulfilledCheckLocationSettings = false;
    }

    @Override
    public void initRequestLocation() {
        locationStore = new LocationStore(context);
        if (!shouldStart) {
            this.client = new GoogleApiClient.Builder(context)
                    .addApi(LocationServices.API)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .build();

            client.connect();
        } else {
            logger.d("already started");
        }
    }

    private LocationRequest createRequest() {
        LocationRequest request = LocationRequest.create()
                .setFastestInterval(params.getInterval())
                .setInterval(params.getInterval())
                .setSmallestDisplacement(params.getDistance());

        if (params.getFastInterval() > 0) {
            request.setFastestInterval(params.getFastInterval());
        }

        switch (params.getAccuracy()) {
            case HIGH:
                request.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
                break;
            case MEDIUM:
                request.setPriority(LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY);
                break;
            case LOW:
                request.setPriority(LocationRequest.PRIORITY_LOW_POWER);
                break;
            case LOWEST:
                request.setPriority(LocationRequest.PRIORITY_NO_POWER);
                break;
        }

        if (singleUpdate) {
            request.setNumUpdates(1);
        }

        return request;
    }

    @Override
    public void startDetectLocation() {
        if (listener == null) {
            logger.d("Listener is null, you sure about this?");
        }
        locationRequest = createRequest();

        if (client.isConnected()) {
            startUpdating(locationRequest);
        } else if (stopped) {
            shouldStart = true;
            client.connect();
            stopped = false;
        } else {
            shouldStart = true;
            logger.d("still not connected - scheduled start when connection is ok");
        }
    }

    private void startUpdating(LocationRequest request) {
        // TODO wait until the connection is done and retry
        if (checkLocationSettings && !fulfilledCheckLocationSettings) {
            logger.d("startUpdating wont be executed for now, as we have to test the location settings before");
            checkLocationSettings();
            return;
        }
        if (client.isConnected()) {
            if (checkGpsPermission()) {
                LocationServices.FusedLocationApi.requestLocationUpdates(client, request, this).setResultCallback(this);
            }
        } else {
            logger.w("startUpdating executed without the GoogleApiClient being connected!!");
        }
    }

    private void checkLocationSettings() {
        LocationSettingsRequest request = new LocationSettingsRequest.Builder().addLocationRequest(locationRequest).build();
        LocationServices.SettingsApi.checkLocationSettings(client, request).setResultCallback(settingsResultCallback);
    }

    @Override
    public void stopDetectLocation() {
        logger.d("stop");
        if (client.isConnected()) {
            LocationServices.FusedLocationApi.removeLocationUpdates(client, this);
            client.disconnect();
        }
        fulfilledCheckLocationSettings = false;
        shouldStart = false;
        stopped = true;
    }

    @Override
    public Location getLastLocation() {
        if (client != null && client.isConnected()) {
            if (checkGpsPermission()) {
                Location location = LocationServices.FusedLocationApi.getLastLocation(client);
                if (location != null) {
                    return location;
                }
            }
        }
        return getCacheLocationFromStore(GMS_ID);
    }

    @Override
    public void onConnected(Bundle bundle) {
        logger.d("onConnected");
        if (shouldStart) {
            startUpdating(locationRequest);
        }
        if (googlePlayServicesListener != null) {
            googlePlayServicesListener.onConnected(bundle);
        }
    }

    @Override
    public void onConnectionSuspended(int i) {
        logger.d("onConnectionSuspended " + i);
        if (googlePlayServicesListener != null) {
            googlePlayServicesListener.onConnectionSuspended(i);
        }
    }

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
        logger.d("onConnectionFailed " + connectionResult.toString());
        if (googlePlayServicesListener != null) {
            googlePlayServicesListener.onConnectionFailed(connectionResult);
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        publishLocationReceive(location, GMS_ID);
    }

    @Override
    public void onResult(@NonNull Status status) {
        if (status.isSuccess()) {
            logger.d("Locations update request successful");
        } else if (status.hasResolution() && context instanceof Activity) {
            logger.w("Unable to register, but we can solve this - will startActivityForResult. You should hook into the Activity onActivityResult and call this provider onActivityResult method for continuing this call flow.");
            try {
                status.startResolutionForResult((Activity) context, REQUEST_START_LOCATION_FIX);
            } catch (IntentSender.SendIntentException e) {
                logger.e(e, "problem with startResolutionForResult");
            }
        } else {
            // No recovery. Weep softly or inform the user.
            logger.e("Registering failed: " + status.getStatusMessage());
        }
    }

    /**
     * @return TRUE if active, FALSE if the settings wont be checked before launching the location updates request
     */
    public boolean isCheckingLocationSettings() {
        return checkLocationSettings;
    }

    /**
     * Sets whether or not we should request (before starting updates) the availability of the
     * location settings and act upon it.
     *
     * @param allowingLocationSettings TRUE to show the dialog if needed, FALSE otherwise (default)
     */
    public void setCheckLocationSettings(boolean allowingLocationSettings) {
        this.checkLocationSettings = allowingLocationSettings;
    }

    /**
     * This method should be called in the onActivityResult of the calling activity whenever we are
     * trying to implement the Check Location Settings fix dialog.
     *
     * @param requestCode
     * @param resultCode
     * @param data
     */
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CHECK_SETTINGS) {
            switch (resultCode) {
                case Activity.RESULT_OK:
                    logger.i("User agreed to make required location settings changes.");
                    fulfilledCheckLocationSettings = true;
                    startUpdating(locationRequest);
                    break;
                case Activity.RESULT_CANCELED:
                    logger.i("User chose not to make required location settings changes.");
                    stop();
                    break;
            }
        } else if (requestCode == REQUEST_START_LOCATION_FIX) {
            switch (resultCode) {
                case Activity.RESULT_OK:
                    logger.i("User fixed the problem.");
                    startUpdating(locationRequest);
                    break;
                case Activity.RESULT_CANCELED:
                    logger.i("User chose not to fix the problem.");
                    stop();
                    break;
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == LOCATION_PERMISSION_ID && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startUpdating(locationRequest);
        }
    }

}
