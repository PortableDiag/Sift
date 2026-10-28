package com.sift.explorer.ui;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.sift.explorer.R;
import com.sift.explorer.api.ApiPrefs;
import com.sift.explorer.api.PhoneApi;
import com.sift.explorer.api.PhoneApiAccess;
import com.sift.explorer.util.ThemePrefs;

/**
 * PhoneAPI asks for a local API key ({@code com.phoneapi.action.GRANT_KEY}, provider contract
 * §3). Only PhoneAPI's release build, started for a result, gets past the first check; then the
 * owner picks what the key may do on Sift's own screen. The key and the proof key leave only as
 * this activity's result.
 */
public class PhoneApiGrantActivity extends AppCompatActivity {

    private static final String TAG = "SiftApi";

    @Override protected void onCreate(Bundle s) {
        ThemePrefs.applyTheme(this);
        super.onCreate(s);
        setResult(RESULT_CANCELED);
        String caller = getCallingPackage();
        if (caller == null || !PhoneApi.callerTrusted(caller, PhoneApiAccess.certs(this, caller))) {
            Log.w(TAG, "phoneapi: key request refused ("
                    + (caller == null ? "not started for a result" : caller + " is not PhoneAPI's release build") + ")");
            finish();
            return;
        }
        int eventPort = getIntent().getIntExtra(PhoneApi.EXTRA_EVENT_PORT, 0);
        String eventKey = getIntent().getStringExtra(PhoneApi.EXTRA_EVENT_KEY);

        setContentView(R.layout.activity_phoneapi_grant);

        StringBuilder body = new StringBuilder(getString(R.string.phoneapi_body));
        if (!ApiPrefs.enabled(this)) body.append(' ').append(getString(R.string.phoneapi_turns_on));
        PhoneApi.Grant current = PhoneApiAccess.grant(this);
        if (current != null) body.append(' ').append(getString(R.string.phoneapi_replaces));
        ((TextView) findViewById(R.id.body)).setText(body);

        MaterialSwitch see = findViewById(R.id.see);
        MaterialSwitch change = findViewById(R.id.change);
        MaterialButton allow = findViewById(R.id.allow);
        // Off for a first key, as the current key has them for a new one; a key that may do nothing isn't worth minting.
        see.setSaveEnabled(false);
        change.setSaveEnabled(false);
        see.setChecked(current != null && current.see);
        change.setChecked(current != null && current.change);
        Runnable sync = () -> allow.setEnabled(see.isChecked() || change.isChecked());
        sync.run();
        see.setOnCheckedChangeListener((b, on) -> sync.run());
        change.setOnCheckedChangeListener((b, on) -> sync.run());

        findViewById(R.id.deny).setOnClickListener(v -> {
            Log.i(TAG, "phoneapi: key request declined");
            finish();
        });
        allow.setOnClickListener(v -> {
            PhoneApi.Grant g = PhoneApiAccess.create(this, see.isChecked(), change.isChecked(), eventPort, eventKey);
            setResult(RESULT_OK, new Intent()
                    .putExtra("api_key", g.key)
                    .putExtra("proof_key", PhoneApi.b64url(g.proofKey))
                    .putExtra("port", ApiPrefs.port(this)));
            finish();
        });
    }
}
