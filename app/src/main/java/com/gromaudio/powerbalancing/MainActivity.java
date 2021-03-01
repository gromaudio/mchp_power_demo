package com.gromaudio.powerbalancing;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.bumptech.glide.Glide;

import java.util.Random;

import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;

public class MainActivity extends AppCompatActivity implements HubManager.IHubListener {
    private static final String TAG = "PB:MainActivity";
    private static final boolean DEBUG = true;

    private static final float MAX_TOTAL_POWER = 100;
    private static final float DEFAULT_MAX_PORT_POWER = 50;

    @BindView(R.id.textView)
    TextView mTitle;
    @BindView(R.id.port2_thermal_state)
    TextView mPort2ThermalState;
    @BindView(R.id.port2_no_device_connected)
    TextView mPort2NoDeviceConnected;
    @BindView(R.id.port2_connection_speed)
    ImageView mPort2ConnectionSpeed;
    @BindView(R.id.port1_connected_w)
    TextView mPort1ConnectedW;
    @BindView(R.id.port2_connected_va)
    TextView mPort2ConnectedVa;
    @BindView(R.id.port2_connected)
    ConstraintLayout mPort2Connected;
    @BindView(R.id.port2_progress)
    ProgressBar mPort2Progress;
    @BindView(R.id.port2_available_port_power)
    TextView mPort2AvailablePortPower;
    @BindView(R.id.maximum_total_system_power)
    TextView mMaximumTotalSystemPower;
    @BindView(R.id.remaining_total_system_power)
    TextView mRemainingTotalSystemPower;
    @BindView(R.id.port1_progress)
    ProgressBar mPort1Progress;
    @BindView(R.id.port1_available_port_power)
    TextView mPort1AvailablePortPower;
    @BindView(R.id.port1_no_device_connected)
    TextView mPort1NoDeviceConnected;
    @BindView(R.id.port1_connection_speed)
    ImageView mPort1ConnectionSpeed;
    @BindView(R.id.port2_connected_w)
    TextView mPort2ConnectedW;
    @BindView(R.id.port1_connected_va)
    TextView mPort1ConnectedVa;
    @BindView(R.id.port1_connected)
    ConstraintLayout mPort1Connected;
    @BindView(R.id.port1_thermal_state)
    TextView mPort1ThermalState;

    private ConnectionPowerState mPort1 = new ConnectionPowerState();
    private ConnectionPowerState mPort2 = new ConnectionPowerState();
    private float mMaxTotalPower = MAX_TOTAL_POWER;
    private float mRemainingTotalPower = MAX_TOTAL_POWER;

    private boolean mRandomDebugMode = false;

    private HubManager mHubManager;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (DEBUG) {
            Log.d(TAG, "onCreate()");
        }
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ButterKnife.bind(this);
        init();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mHubManager!=null) {
            mHubManager.close();
            mHubManager = null;
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        if (DEBUG) {
            Log.d(TAG, "onNewIntent(" + intent + ")");
        }
        super.onNewIntent(intent);
        if (mHubManager!=null) {
            mHubManager.stop();
            mHubManager.update();
        }
    }

    @Override
    protected void onPause() {
        if (DEBUG) {
            Log.d(TAG, "onPause()");
        }
        super.onPause();
        if (mHubManager!=null) {
            mHubManager.stop();
        }
    }

    @Override
    protected void onResume() {
        if (DEBUG) {
            Log.d(TAG, "onResume()");
        }
        super.onResume();
        if (mHubManager!=null) {
            mHubManager.update();
        }
    }

    void init() {
        setThermalState(Port.PORT_1, ThermalState.NOT_IMPLEMENTED);
        setThermalState(Port.PORT_2, ThermalState.NOT_IMPLEMENTED);
        setDisconnected(Port.PORT_1, DEFAULT_MAX_PORT_POWER);
        setDisconnected(Port.PORT_2, DEFAULT_MAX_PORT_POWER);

        mMaximumTotalSystemPower.setText(getString(R.string.maximum_total_system_power, mMaxTotalPower));
        mRemainingTotalSystemPower.setText(getString(R.string.remaining_total_system_power, mRemainingTotalPower));

        mHubManager = new HubManager(getBaseContext(), this);
    }

    void setThermalState(Port port, ThermalState thermalState) {
        TextView portThermalState = port == Port.PORT_1 ? mPort1ThermalState : mPort2ThermalState;
        portThermalState.setBackgroundResource(thermalState.getColor());
        portThermalState.setText(thermalState.getTitle());
    }

    void setDisconnected(Port port, float maxP) {
        boolean port1 = port == Port.PORT_1;
        ConnectionPowerState portState = port1 ? mPort1 : mPort2;
        portState.setConnected(false);
        portState.setMaxP(maxP);

        updateRemainingPower();

        View hide = port1 ? mPort1Connected : mPort2Connected;
        View show = port1 ? mPort1NoDeviceConnected : mPort2NoDeviceConnected;
        hide.setVisibility(View.INVISIBLE);
        show.setVisibility(View.VISIBLE);
        setProgress(port, 0, (maxP < mRemainingTotalPower ? maxP : mRemainingTotalPower) );
    }

    void setConnected(Port port, float w, float v, float a, float maxP) {
        boolean port1 = port == Port.PORT_1;
        ConnectionPowerState portState = port1 ? mPort1 : mPort2;
        TextView portW = port1 ? mPort1ConnectedW : mPort2ConnectedW;
        TextView portVA = port1 ? mPort1ConnectedVa : mPort2ConnectedVa;
        ImageView icon = port1 ? mPort1ConnectionSpeed : mPort2ConnectionSpeed;
        View hide = port1 ? mPort1NoDeviceConnected : mPort2NoDeviceConnected;
        View show = port1 ? mPort1Connected : mPort2Connected;
        hide.setVisibility(View.INVISIBLE);
        show.setVisibility(View.VISIBLE);
        portState.setW(w);
        portState.setV(v);
        portState.setA(a);
        portState.setMaxP(maxP);
        portState.setConnected(true);

        updateRemainingPower();

        portW.setText(getString(R.string.w, w));
        portVA.setText(getString(R.string.va, v, a));

        Speed speed = Speed.SLOW;
        if (w >= Speed.FAST.getThreshold())
            speed = Speed.FAST;
        else if (w >= Speed.AVERAGE.getThreshold())
            speed = Speed.AVERAGE;

        Glide.with(this).asGif().load(port1 ? speed.getLeft() : speed.getRight()).into(icon);
        float remainingTotal = mRemainingTotalPower + w;
        setProgress(port, w, (maxP < remainingTotal ? maxP : remainingTotal));
    }

    void setProgress(Port port, float w, float maxP) {
        boolean port1 = port == Port.PORT_1;
        ProgressBar portProgress = port1 ? mPort1Progress : mPort2Progress;
        portProgress.setMax((int)maxP); //available port power
        portProgress.setProgress((int) w);
        TextView powerRemaining = port1 ? mPort1AvailablePortPower : mPort2AvailablePortPower;
        powerRemaining.setText(getString(R.string.w, maxP - w));
    }

    @OnClick(R.id.logo)
    void onLogoClick() {
        mRandomDebugMode = true;
        setRandomData(Port.PORT_1);
        setRandomData(Port.PORT_2);
    }

    void setRandomData(Port port) {
        Random random = new Random();
        boolean connected = random.nextBoolean();
        if (!connected) {
            setDisconnected(port, DEFAULT_MAX_PORT_POWER);
            return;
        }

        float v = randomFloatInRange(0, 10);
        float a = randomFloatInRange(0, 5);
        setConnected(port, (v*a), v, a, DEFAULT_MAX_PORT_POWER);
        setThermalState(port, ThermalState.class.getEnumConstants()
                [random.nextInt(ThermalState.class.getEnumConstants().length)]);
    }

    float randomFloatInRange(float min, float max) {
        return new Random().nextFloat() * (max - min) + min;
    }

    private void updateRemainingPower() {
        mRemainingTotalPower = mMaxTotalPower - (mPort1.isConnected()?mPort1.getW():0) - (mPort2.isConnected()?mPort2.getW():0);
        mMaximumTotalSystemPower.setText(getString(R.string.maximum_total_system_power, mMaxTotalPower));
        mRemainingTotalSystemPower.setText(getString(R.string.remaining_total_system_power, mRemainingTotalPower));
    }

    //IHubListener
    @Override
    public void onPortStatus(int port, boolean attached, boolean negotiated, boolean orientation, boolean cap_mismatch,
                             float maxpower, float voltage, float current, float power, float sys_pwr, ThermalState ts) {
        if (DEBUG) {
            Log.d(TAG, "onPortStatus(" + port + ")");
        }
        mRandomDebugMode = false;
        mMaxTotalPower = sys_pwr;
        Port p = (port==1) ? Port.PORT_1 : Port.PORT_2;
        if (attached) {
            setConnected(p, power, voltage, current, maxpower);
            setThermalState(p, ts);
        } else {
            setDisconnected(p, maxpower);
            setThermalState(p, ts);
        }
    }

    @Override
    public void onHubStatus(int hubStatus) {
        if (mRandomDebugMode) {
            return;
        }
        if (hubStatus == HubManager.HUB_STATUS_DISCONNECTED) {
            mTitle.setBackgroundColor(Color.parseColor("#FF000000")); //
            setDisconnected(Port.PORT_1, DEFAULT_MAX_PORT_POWER);
            setDisconnected(Port.PORT_2, DEFAULT_MAX_PORT_POWER);
            setThermalState(Port.PORT_1, ThermalState.NOT_IMPLEMENTED);
            setThermalState(Port.PORT_2, ThermalState.NOT_IMPLEMENTED);
        } else if (hubStatus == HubManager.HUB_STATUS_CONNECTED) {
            mTitle.setBackgroundColor(Color.parseColor("#00FF00")); //green
        } else if (hubStatus == HubManager.HUB_STATUS_ERRORS) {
            mTitle.setBackgroundColor(Color.parseColor("#FF0000")); //red
        }
    }

}
