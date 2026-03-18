package org.opencpn;

import android.util.Log;
import android.os.Bundle;
import android.content.Intent;

import androidx.fragment.app.FragmentActivity;


/**
 * Stub replacement for the Google Maps activity.
 * Google Maps dependency has been removed for GMS-free deployment.
 * This activity immediately finishes and returns a no-op result to the caller.
 */
public class OCPNMapsActivity extends FragmentActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i("OpenCPN", "OCPNMapsActivity: Google Maps not available in this build, returning.");
        finish();
    }

    @Override
    public void finish() {
        Bundle b = new Bundle();
        b.putString("finalPosition", "0;0;0;1");
        Intent i = new Intent();
        i.putExtras(b);
        setResult(RESULT_OK, i);
        super.finish();
    }
}
