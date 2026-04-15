package org.opencpn;

import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.ForegroundServiceStartNotAllowedException;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.pm.ServiceInfo;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.GpsStatus;
import android.location.GpsSatellite;
import android.location.OnNmeaMessageListener;

import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.HandlerThread;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.app.Activity;
import android.os.Handler;

import androidx.core.app.NotificationCompat;

import java.lang.reflect.Method;
import java.util.List;
import java.lang.Math;
import java.lang.Iterable;
import java.util.Iterator;

import org.opencpn.OCPNGpsNmeaListener;
import org.opencpn.OCPNNativeLib;



public class GPSServer extends Service implements LocationListener {

    public final static int GPS_OFF = 0;
    public final static int GPS_ON = 1;
    public  final static int GPS_PROVIDER_AVAILABLE = 2;
    public final static int GPS_SHOWPREFERENCES = 3;
    public final static int GPS_STOPSERVICE = 4;

    private Context mContext;

    public String status_string;

    boolean isThreadStarted = false;
    HandlerThread mLocationHandlerThread;
    HandlerThread mReqThread;
    Handler mReqHandler;
    Handler mTickerHandler;

    OCPNGpsNmeaListener mNMEAListener;

    OCPNNativeLib mNativeLib;
    MyNMEAMessageListener mNMEAMessageListener;
    MYGpsNmeaListener mGPSNMEAListener;

    // flag for GPS status
    boolean isGPSEnabled = false;

    // flag for network status
    boolean isNetworkEnabled = false;

    // flag for GPS status
    boolean canGetLocation = false;

    Location mLastLocation; // location
    double latitude; // latitude
    double longitude; // longitude
    float course;
    float speed;

    private GpsStatus mStatus;
    private MyListener mMyListener;
    long mLastLocationMillis;
    long mlastNMEAMillis;
    // mNMEAEverReceived removed — ticker now runs persistently and
    // self-suppresses via the silenceMs check when real NMEA flows.
    boolean isGPSFix = false;
    public int m_watchDog = 0;
    boolean isGPSStarted = false;
    int m_tick;

    // The minimum distance to change Updates in meters
    private static final long MIN_DISTANCE_CHANGE_FOR_UPDATES = 1; // 1 meter

    // The minimum time between updates in milliseconds
    private static final long MIN_TIME_BW_UPDATES = 1000; // 1 second

    // Declaring a Location Manager
    protected LocationManager locationManager;

    private final IBinder binder = new LocalBinder();

    /**
     * Class used for the client Binder.  Because we know this service always
     * runs in the same process as its clients, we don't need to deal with IPC.
     */
    public class LocalBinder extends Binder {
        public GPSServer getService() {
            // Return this instance of LocalService so clients can call public methods
            return GPSServer.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        mNativeLib = OCPNNativeLib.getInstance();
        mContext = getApplicationContext();
        return binder;
    }

    public GPSServer(){
    }

    private boolean isInBackground(){
        ActivityManager.RunningAppProcessInfo myProcess = new ActivityManager.RunningAppProcessInfo();
        ActivityManager.getMyMemoryState(myProcess);
        Boolean isInBackground = myProcess.importance != ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
        Log.d("OpenCPN", myProcess.processName + " " + myProcess.importance + " " + isInBackground);
        return  isInBackground;
    }

    @Override
    public void onCreate() {
        Log.d("OpenCPN", "GPS Service onCreate");

        if (Build.VERSION.SDK_INT >= 26) {
            String CHANNEL_ID = "my_channel_01";
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    "Channel human readable title",
                    NotificationManager.IMPORTANCE_DEFAULT);

            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(channel);

            Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("")
                    .setContentText("").build();

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // API 34
                    startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
                } else {
                    startForeground(1, notification);
                }
            } catch (Exception e) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        e instanceof ForegroundServiceStartNotAllowedException
                ) {
                    Log.i("OpenCPN", "GPS Service Not Started from background");
                    Log.i("OpenCPN", "Try disable battery optimization");
                }
            }
        }
    }
    @Override
    public void onDestroy() {
        Log.i("OpenCPN", "GPS Service onDestroy");
    }

     private class MyListener implements GpsStatus.Listener {
        @Override
        public void onGpsStatusChanged(int event) {
//            Log.i("OpenCPN", "StatusListener Event");

//            if(null != locationManager){
//                mStatus = locationManager.getGpsStatus(null);
//            }


            switch (event) {
                case GpsStatus.GPS_EVENT_STARTED:
                    Log.i("OpenCPN", "GPS_EVENT_STARTED Event");
                    isGPSStarted = true;
                    isGPSFix = false;
                    break;

                case GpsStatus.GPS_EVENT_STOPPED:
                    Log.i("OpenCPN", "GPS_EVENT_STOPPED Event");
                    //isGPSFix = false;
                    isGPSStarted = false;
                    break;

                case GpsStatus.GPS_EVENT_FIRST_FIX:
                    Log.i("OpenCPN", "GPS_EVENT_FIRST_FIX Event");
                    isGPSFix = true;
                    break;

/*
            // Removed this test as not necessary...
            // But, if desired, could probably be re-engaged if
            //  we move the locationManager.getGpsStatus(null) call (see line 87) to inside this case.
            //  It seems that getGPSStatus(null) may crash if there has not been a satellite status update,
            //  so should not be called until one is sure that has happened, evidenced by this case GpsStatus.GPS_EVENT_SATELLITE_STATUS:


                case GpsStatus.GPS_EVENT_SATELLITE_STATUS:
//                    Log.i("OpenCPN", "GPS_EVENT_SATELLITE_STATUS Event");

                      int nSatsUsed = 0;
                      boolean bSatsValid = true;
                      // int maxSatellites = gpsStatus.getMaxSatellites();    // appears fixed at 255

                      // The Android GPS locator service runs in another thread.
                      // So, the list of satellites may be changed while our thread is waling the iterator.
                      // That may provoke a NoSuchElementException exception, so we handle that case quietly...

                      try{
                             Iterable<GpsSatellite>satellites = mStatus.getSatellites();
                             Iterator<GpsSatellite>satI = satellites.iterator();

                             while (satI.hasNext()) {
                             GpsSatellite satellite = satI.next();
//                             Log.i("DEBUGGER_TAG", "onGpsStatusChanged(): " + satellite.getPrn() + "," + satellite.usedInFix() + "," + satellite.getSnr() + "," + satellite.getAzimuth() + "," + satellite.getElevation());
                             if(satellite.usedInFix())
                             nSatsUsed++;
                             }
                      }catch(Exception e){
                            Log.i("OpenCPN", "GPS_EVENT_SATELLITE_STATUS Exception");
                            bSatsValid = false;
                      }


                    if( bSatsValid && (nSatsUsed < 3))
                        isGPSFix = false;

                    break;
*/
            }
        }
    }

//    https://developer.android.com/reference/android/location/LocationManager.html#addNmeaListener(android.location.OnNmeaMessageListener,%2520android.os.Handler)

    private class MYGpsNmeaListener implements GpsStatus.NmeaListener{


        @Override
        public void onNmeaReceived(long timestamp, String nmea) {

            mlastNMEAMillis = SystemClock.elapsedRealtime();

            String filterNMEA = nmea;
            filterNMEA = filterNMEA.replaceAll("[^\\x0A\\x0D\\x20-\\x7E]", "");
//            Log.i("OpenCPN", "Listener: " + filterNMEA);

            // Reset the dog.
            if( nmea.contains("RMC") ){
                m_watchDog = 0;
            }

            if(null != mNativeLib){
                mNativeLib.processNMEAInt( filterNMEA );
            }
        }
    }




    private class MyNMEAMessageListener implements OnNmeaMessageListener {
        @Override
        public void onNmeaMessage( String message, long timestamp ) {

            //Log.i("OpenCPN", "MyNMEAMessageListener: onNMEAMessage: "+ message );

            mlastNMEAMillis = SystemClock.elapsedRealtime();

            String filterNMEA = message;
            filterNMEA = filterNMEA.replaceAll("[^\\x0A\\x0D\\x20-\\x7E]", "");
            //Log.i("OpenCPN", "MyNMEAMessageListener: onNMEAMessage: " + filterNMEA );

            // Reset the dog.
            if( message.contains("RMC") ){
                //Location  location = getLocation();
                //float accuracy = location.getAccuracy();
                //Log.i("OpenCPN", "Accuracy: " + String.valueOf(accuracy) );
                //Log.i("OpenCPN", "onNMEAMessage: " + filterNMEA);

                m_watchDog = 0;
            }
            if(null != mNativeLib){
                //Log.i("OpenCPN", "MyNMEAMessageListener: onNMEAMessage : calling mNativeLib.processNMEAInt" );
                mNativeLib.processNMEAInt( filterNMEA );
            }

        }
    }


    public GPSServer(Context context, OCPNNativeLib nativelib) {
    }

    public String doService( int parm )
    {
        Log.d("OpenCPN", "GPS Service doService");

        String ret_string = "???";
        locationManager = (LocationManager) mContext.getSystemService(LOCATION_SERVICE);

        switch (parm){
            case GPS_OFF:
            Log.i("OpenCPN", "GPS Service doService :GPS OFF");

            // Cancel the keepalive ticker first, so it stops scheduling itself
            if (mTickerHandler != null) {
                mTickerHandler.removeCallbacksAndMessages(null);
            }

            if(locationManager != null){
                if(isThreadStarted){
                    locationManager.removeUpdates(GPSServer.this);

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {    // 24
                        if (mNMEAMessageListener != null) {
                            locationManager.removeNmeaListener(mNMEAMessageListener);
                        }
                    }
                    else{
                        try {
                            //noinspection JavaReflectionMemberAccess
                            Method removeNmeaListener =
                                    LocationManager.class.getMethod("removeNmeaListener", GpsStatus.NmeaListener.class);
                            removeNmeaListener.invoke(locationManager, mGPSNMEAListener);
                        } catch (Exception exception) {
                            // TODO
                        }
                    }

                    isThreadStarted = false;
                }
            }
            isGPSEnabled = false;

            // Quit the request handler thread to release resources
            if (mReqThread != null && mReqThread.isAlive()) {
                mReqThread.quitSafely();
                mReqThread = null;
                mReqHandler = null;
            }

            ret_string = "GPS_OFF OK";
            break;

            case GPS_ON:
                Log.i("OpenCPN", "GPS Service doService :GPS ON");

                isGPSEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);

                if(isGPSEnabled){
                    Log.i("OpenCPN", "GPS Service doService : GPS is Enabled");
                }
                else{
                    Log.i("OpenCPN", "GPS Service doService : GPS is <<<<DISABLED>>>>");
                    ret_string = "GPS is disabled";
                    status_string = ret_string;
                    return ret_string;
                }

                if(!isThreadStarted){
                    Log.i("OpenCPN", "GPS Service doService : Start Thread");

                    // Reuse or create the request handler thread
                    if (mReqThread == null || !mReqThread.isAlive()) {
                        mReqThread = new HandlerThread("GPSRequestThread");
                        mReqThread.start();
                        mReqHandler = new Handler(mReqThread.getLooper());
                    }

                    final Handler reqHandler = mReqHandler;

                    Runnable Req = new Runnable() {
                                            public void run() {
                                                LocationManager lm = (LocationManager) mContext.getSystemService(LOCATION_SERVICE);

                                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {    // 24
                                                    mNMEAMessageListener = new MyNMEAMessageListener();
                                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) { // 31
                                                        lm.addNmeaListener(java.util.concurrent.Executors.newSingleThreadExecutor(), mNMEAMessageListener);
                                                        Log.i("OpenCPN", "GPS Service doService : adding MyNMEAMessageListener with Executor");
                                                    } else {
                                                        lm.addNmeaListener(mNMEAMessageListener, reqHandler);
                                                        Log.i("OpenCPN", "GPS Service doService : adding MyNMEAMessageListener with Handler");
                                                    }
                                                } else {
                                                    mGPSNMEAListener = new MYGpsNmeaListener();
                                                    try {
                                                        //noinspection JavaReflectionMemberAccess
                                                        Method addNmeaListener =
                                                                LocationManager.class.getMethod("addNmeaListener", GpsStatus.NmeaListener.class);
                                                        addNmeaListener.invoke(lm, mGPSNMEAListener);
                                                    } catch (Exception exception) {
                                                        // TODO
                                                    }
                                                }

                                                Log.i("OpenCPN", "GPS Service doService : Requesting Location Updates");
                                                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, GPSServer.this);
                                            }};

                    // Schedule listener registration with short delay
                    mReqHandler.postDelayed(Req, 100);

                    // Start the keepalive ticker.
                    // Runs persistently every 3 seconds.  When real NMEA is flowing,
                    // mlastNMEAMillis stays fresh so silenceMs < 5000 and no synthetic
                    // data is sent.  When NMEA goes silent (cold start, battery
                    // throttling, brief signal loss) the ticker sends synthetic
                    // RMC + GGA + GSA to keep OpenCPN's watchdog happy and its
                    // signal-quality indicator accurate.
                    mlastNMEAMillis = 0; // treat as silent immediately so first tick fires

                    HandlerThread tickerThread = new HandlerThread("GPSKeepalive");
                    tickerThread.start();
                    mTickerHandler = new Handler(tickerThread.getLooper());

                    Runnable ticker = new Runnable() {
                        @Override
                        public void run() {
                            if (!isThreadStarted) return; // GPS_OFF called; stop ticking

                            long silenceMs = SystemClock.elapsedRealtime() - mlastNMEAMillis;
                            if (silenceMs > 5000) {
                                // Real NMEA silent — send synthetic RMC+GGA+GSA so
                                // OpenCPN keeps position and shows proper signal quality
                                try {
                                    Location last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                                    if (last != null && mNativeLib != null) {
                                        Log.i("OpenCPN", "GPS keepalive: synthetic NMEA (silent " + silenceMs/1000 + "s)");
                                        mNativeLib.processNMEAInt(createRMC(last));
                                        mNativeLib.processNMEAInt(createGGA(last));
                                        mNativeLib.processNMEAInt(createGSA(last));
                                    }
                                } catch (SecurityException e) {
                                    Log.w("OpenCPN", "GPS keepalive: " + e.getMessage());
                                }
                            }

                            if (mTickerHandler != null && isThreadStarted) {
                                mTickerHandler.postDelayed(this, 3000);
                            }
                        }
                    };

                    mTickerHandler.post(ticker); // fire immediately for first tick

                    isThreadStarted = true;
                }

                ret_string = "GPS_ON OK";
                break;

            case GPS_PROVIDER_AVAILABLE:
            if(hasGPSDevice( mContext )){
                    ret_string = "YES";
                    Log.i("OpenCPN", "Provider yes");
                }
                else{
                    ret_string = "NO";
                    Log.i("OpenCPN", "Provider no");
                }

                break;

            case GPS_SHOWPREFERENCES:
                showSettingsAlert();
                break;

            case GPS_STOPSERVICE:
                stopForeground(true);
                stopSelf();
                break;

        }   // switch


        status_string = ret_string;
        return ret_string;
     }



     public boolean hasGPSDevice(Context context)
     {

 //        This code crashes unless run from the GUI thread, so is moved to the QtActivity initialization
 //        PackageManager packMan = getPackageManager();
 //        return packMan.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS);

    // This code produces false positive for some generic android tablets.
         final LocationManager mgr = (LocationManager)context.getSystemService(Context.LOCATION_SERVICE);
         if ( mgr == null )
            return false;
         final List<String> providers = mgr.getAllProviders();
         if ( providers == null )
            return false;
         return providers.contains(LocationManager.GPS_PROVIDER);

     }


    public Location getLocation() {
        try {
            locationManager = (LocationManager) mContext
                    .getSystemService(LOCATION_SERVICE);

            // getting GPS status
            isGPSEnabled = locationManager
                    .isProviderEnabled(LocationManager.GPS_PROVIDER);

            // getting network status
            isNetworkEnabled = locationManager
                    .isProviderEnabled(LocationManager.NETWORK_PROVIDER);

            if (!isGPSEnabled && !isNetworkEnabled) {
                // no network provider is enabled
            } else {
                this.canGetLocation = true;
                // First get location from Network Provider
                if (isNetworkEnabled) {
                    locationManager.requestLocationUpdates(
                            LocationManager.NETWORK_PROVIDER,
                            MIN_TIME_BW_UPDATES,
                            MIN_DISTANCE_CHANGE_FOR_UPDATES, this);
                    Log.d("Network", "Network");
                    if (locationManager != null) {
                        mLastLocation = locationManager
                                .getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                        if (mLastLocation != null) {
                            latitude = mLastLocation.getLatitude();
                            longitude = mLastLocation.getLongitude();
                        }
                    }
                }
                // if GPS Enabled get lat/long using GPS Services
                if (isGPSEnabled) {
                    if (mLastLocation == null) {
                        locationManager.requestLocationUpdates(
                                LocationManager.GPS_PROVIDER,
                                MIN_TIME_BW_UPDATES,
                                MIN_DISTANCE_CHANGE_FOR_UPDATES, this);
                        Log.d("OpenCPN", "GPS Enabled");
                        if (locationManager != null) {
                            mLastLocation = locationManager
                                    .getLastKnownLocation(LocationManager.GPS_PROVIDER);
                            if (mLastLocation != null) {
                                latitude = mLastLocation.getLatitude();
                                longitude = mLastLocation.getLongitude();
                            }
                        }
                    }
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        return mLastLocation;
    }

    /**
     * Stop using GPS listener
     * Calling this function will stop using GPS in your app
     * */
    public void stopUsingGPS(){
        if(locationManager != null){
            locationManager.removeUpdates(GPSServer.this);
        }
    }

    /**
     * Function to get latitude
     * */
    public double getLatitude(){
        if(mLastLocation != null){
            latitude = mLastLocation.getLatitude();
        }

        // return latitude
        return latitude;
    }

    /**
     * Function to get longitude
     * */
    public double getLongitude(){
        if(mLastLocation != null){
            longitude = mLastLocation.getLongitude();
        }

        // return longitude
        return longitude;
    }

    /**
     * Function to check GPS/wifi enabled
     * @return boolean
     * */
    public boolean canGetLocation() {
        return this.canGetLocation;
    }

    /**
     * Function to show settings alert dialog
     * On pressing Settings button will lauch Settings Options
     * */
    public void showSettingsAlert(){
        AlertDialog.Builder alertDialog = new AlertDialog.Builder(this);

        // Setting Dialog Title
        alertDialog.setTitle("GPS is settings");

        // Setting Dialog Message
        alertDialog.setMessage("GPS is not enabled. Do you want to go to settings menu?");

        // On pressing Settings button
        alertDialog.setPositiveButton("Settings", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog,int which) {
                Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                mContext.startActivity(intent);
            }
        });

        // on pressing cancel button
        alertDialog.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
            dialog.cancel();
            }
        });

        // Showing Alert Message
        alertDialog.show();
    }

    @Override
    public void onLocationChanged(Location location) {
        //Log.i("OpenCPN", "onLocationChanged");
        if (location == null) return;

        mLastLocation = location;
        mLastLocationMillis = SystemClock.elapsedRealtime();

        // Some devices do not transmit NMEA messages by the established API, for unknown reasons.
        // If we can detect this case, then we can synthesize a GPRMC message here,
        // thus providing a least some usable position information to OCPN.

        // calculate the time since the last NMEA message was received by mNMEAMessageListener
        long deltaTime = mLastLocationMillis - mlastNMEAMillis;
        //Log.i("OpenCPN", Long.toString( deltaTime));
        if(deltaTime > 2000){
            if(null != mNativeLib) {
                mNativeLib.processNMEAInt(createRMC(location));
                mNativeLib.processNMEAInt(createGGA(location));
                mNativeLib.processNMEAInt(createGSA(location));
            }
        }
    }

    @Override
    public void onProviderDisabled(String provider) {
        Log.i("OpenCPN", "onProviderDisabled " + provider);

    }

    @Override
    public void onProviderEnabled(String provider) {
        Log.i("OpenCPN", "onProviderEnabled " + provider);

    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
        Log.i("OpenCPN", "onStatusChanged");

    }


    public static String createRMC(Location location){
        // Create an NMEA sentence
        String s = "$OCRMC,,";
        //if(isGPSStarted && isGPSFix)
            s = s.concat("A,");
        //else
        //    s = s.concat("V,");


        String slat = "";
        double ltt = location.getLatitude();
        if(ltt < 0)
            ltt = -location.getLatitude();

        double d0 = Math.floor(ltt);
        double d1 = ltt-d0;
        double d2 = Math.floor(d1 * 60);
        double d3 = (d1*60.) - d2;

        slat = slat.format("%.0f.%.0f,", (d0 * 100.) + d2, d3 * 10000);

        if(ltt > 0)
            slat = slat.concat("N,");
        else
            slat = slat.concat("S,");


        s = s.concat(slat);

        String slon = "";
        double lot = location.getLongitude();
        if(lot < 0)
            lot = -location.getLongitude();

        d0 = Math.floor(lot);
        d1 = lot-d0;
        d2 = Math.floor(d1 * 60);
        d3 = (d1*60.) - d2;

        if(d0 < 100.)
            slon = "0";
        slon = slon.concat(slon.format("%.0f.%.0f,", (d0 * 100.) + d2, d3 * 10000));

        if(location.getLongitude() > 0)
            slon = slon.concat("E,");
        else
            slon = slon.concat("W,");

        s = s.concat(slon);

        String sspeed = "";
        sspeed = sspeed.format("%.2f,", location.getSpeed() /.5144);
        s = s.concat(sspeed);

        String strack = "";
        strack = strack.format("%.0f,", location.getBearing());
        s = s.concat(strack);

        s = s.concat(",,");      // unused fields

        int checksum = 0;
        for(int i=1 ; i < s.length()-2 ; i++){
            checksum = checksum ^ s.charAt(i);
        }

        s = s.concat("*");    // checksum leader

        String hex = Integer.toHexString(checksum);
        if (hex.length() == 1)
            hex = "0" + hex;
        s += hex.toUpperCase();

        //s = s.concat("\r\n");

//        Log.i("OpenCPN", s);

        return s;
    }

    /**
     * Create a synthetic GGA sentence from an Android Location.
     * Provides fix quality, HDOP and altitude so OpenCPN shows a healthy
     * signal indicator even when raw NMEA is throttled by Android.
     */
    public static String createGGA(Location location) {
        // Derive quality metrics from Android's accuracy estimate
        float accuracy = location.hasAccuracy() ? location.getAccuracy() : 25.0f;
        double hdop = Math.max(0.5, Math.min(10.0, accuracy / 5.0));
        int numSats = 7; // reasonable default
        try {
            Bundle extras = location.getExtras();
            if (extras != null && extras.containsKey("satellites")) {
                numSats = extras.getInt("satellites", 7);
                if (numSats < 1) numSats = 7;
            }
        } catch (Exception ignored) {}

        double alt = location.hasAltitude() ? location.getAltitude() : 0.0;

        // Format latitude in NMEA ddmm.mmmm
        double lat = Math.abs(location.getLatitude());
        int latDeg = (int) Math.floor(lat);
        double latMin = (lat - latDeg) * 60.0;
        String latStr = String.format("%02d%07.4f,%s", latDeg, latMin,
                location.getLatitude() >= 0 ? "N" : "S");

        // Format longitude in NMEA dddmm.mmmm
        double lon = Math.abs(location.getLongitude());
        int lonDeg = (int) Math.floor(lon);
        double lonMin = (lon - lonDeg) * 60.0;
        String lonStr = String.format("%03d%07.4f,%s", lonDeg, lonMin,
                location.getLongitude() >= 0 ? "E" : "W");

        // $OCGGA,,lat,N,lon,E,fixQual,numSats,hdop,alt,M,geoidSep,M,,*cs
        String body = String.format("OCGGA,,%s,%s,1,%02d,%.1f,%.1f,M,,M,,",
                latStr, lonStr, numSats, hdop, alt);

        return "$" + body + "*" + nmeaChecksum(body);
    }

    /**
     * Create a synthetic GSA sentence from an Android Location.
     * Provides DOP values so OpenCPN can assess fix quality.
     */
    public static String createGSA(Location location) {
        float accuracy = location.hasAccuracy() ? location.getAccuracy() : 25.0f;
        double hdop = Math.max(0.5, Math.min(10.0, accuracy / 5.0));
        double vdop = hdop * 1.2; // rough estimate
        double pdop = Math.sqrt(hdop * hdop + vdop * vdop);
        int fixType = location.hasAltitude() ? 3 : 2; // 3D or 2D

        // $OCGSA,A,fixType,,,,,,,,,,,,,pdop,hdop,vdop*cs
        String body = String.format("OCGSA,A,%d,,,,,,,,,,,,%.1f,%.1f,%.1f",
                fixType, pdop, hdop, vdop);

        return "$" + body + "*" + nmeaChecksum(body);
    }

    /** Compute NMEA XOR checksum over body (between $ and *). */
    private static String nmeaChecksum(String body) {
        int cs = 0;
        for (int i = 0; i < body.length(); i++) {
            cs ^= body.charAt(i);
        }
        String hex = Integer.toHexString(cs).toUpperCase();
        return hex.length() == 1 ? "0" + hex : hex;
    }

    /**
     * Returns the {@code location} object as a human readable string.
     * @param location  The {@link Location}.
     */
    static String getLocationText(Location location) {
        return location == null ? "Unknown location" :
                "(" + location.getLatitude() + ", " + location.getLongitude() + ")";
    }

}



//GPSTracker gps = new GPSTracker(this);
//if(gps.canGetLocation()){ // gps enabled} // return boolean true/false

//Getting Latitude and Longitude
//gps.getLatitude(); // returns latitude
//gps.getLongitude(); // returns longitude

//Showing GPS Settings Alert Dialog
//gps.showSettingsAlert();

//Stop using GPS
//gps.stopUsingGPS();
