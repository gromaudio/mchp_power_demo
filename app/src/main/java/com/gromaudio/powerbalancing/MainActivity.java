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

    private static final float MAX_POWER = 60;
    private static final float MAX_TOTAL_POWER = 100;

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
        setDisconnected(Port.PORT_1);
        setDisconnected(Port.PORT_2);

        mMaximumTotalSystemPower.setText(getString(R.string.maximum_total_system_power, MAX_TOTAL_POWER));
        mRemainingTotalSystemPower.setText(getString(R.string.remaining_total_system_power, MAX_TOTAL_POWER));

        mHubManager = new HubManager(getBaseContext(), this);
    }

    void setThermalState(Port port, ThermalState thermalState) {
        TextView portThermalState = port == Port.PORT_1 ? mPort1ThermalState : mPort2ThermalState;
        portThermalState.setBackgroundResource(thermalState.getColor());
        portThermalState.setText(thermalState.getTitle());
    }

    void setDisconnected(Port port) {
        boolean port1 = port == Port.PORT_1;
        ConnectionPowerState portState = port1 ? mPort1 : mPort2;
        portState.setConnected(false);
        View hide = port1 ? mPort1Connected : mPort2Connected;
        View show = port1 ? mPort1NoDeviceConnected : mPort2NoDeviceConnected;
        hide.setVisibility(View.INVISIBLE);
        show.setVisibility(View.VISIBLE);
        setProgress(port, 0);
        setThermalState(port, ThermalState.NOT_IMPLEMENTED);
    }

    void setConnected(Port port, float w, float v, float a) {
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
        portState.setConnected(true);

        portW.setText(getString(R.string.w, w));
        portVA.setText(getString(R.string.va, v, a));

        Speed speed = Speed.SLOW;
        if (w >= Speed.FAST.getThreshold())
            speed = Speed.FAST;
        else if (w >= Speed.AVERAGE.getThreshold())
            speed = Speed.AVERAGE;

        Glide.with(this).asGif().load(port1 ? speed.getLeft() : speed.getRight()).into(icon);
        setProgress(port, w);
    }

    void setProgress(Port port, float w) {
        boolean port1 = port == Port.PORT_1;
        ProgressBar portProgress = port1 ? mPort1Progress : mPort2Progress;
        portProgress.setProgress((int) w);
        TextView powerRemaining = port1 ? mPort1AvailablePortPower : mPort2AvailablePortPower;
        powerRemaining.setText(getString(R.string.w, MAX_POWER - w));
    }

    @OnClick(R.id.logo)
    void onLogoClick() {
        setRandomData(Port.PORT_1);
        setRandomData(Port.PORT_2);
    }

    void setRandomData(Port port) {
        Random random = new Random();
        boolean connected = random.nextBoolean();
        if (!connected) {
            setDisconnected(port);
            return;
        }

        setConnected(port, randomFloatInRange(0, 60), randomFloatInRange(0, 12),
                randomFloatInRange(0, 5));
        setThermalState(port, ThermalState.class.getEnumConstants()
                [random.nextInt(ThermalState.class.getEnumConstants().length)]);

        mRemainingTotalSystemPower.setText(getString(R.string.remaining_total_system_power,
                MAX_TOTAL_POWER - mPort1.getW() - mPort2.getW()));
    }

    float randomFloatInRange(float min, float max) {
        return new Random().nextFloat() * (max - min) + min;
    }

    private void updateRemainingPower() {
        mRemainingTotalSystemPower.setText(getString(R.string.remaining_total_system_power,
                MAX_TOTAL_POWER - (mPort1.isConnected()?mPort1.getW():0) - (mPort2.isConnected()?mPort2.getW():0) ));
    }

    //IHubListener
    @Override
    public void onPortStatus(int port, boolean attached, boolean negotiated, boolean orientation, boolean cap_mismatch,
                             float allocpower, float voltage, float current, float power, float pwr_cap) {
        if (DEBUG) {
            Log.d(TAG, "onPortStatus(" + port + ")");
        }
        Port p = (port==1) ? Port.PORT_1 : Port.PORT_2;
        if (attached) {
            setConnected(p, allocpower, voltage, current);
        } else {
            setDisconnected(p);
        }
        updateRemainingPower();
    }

    @Override
    public void onHubStatus(int hubStatus) {
        if (hubStatus == HubManager.HUB_STATUS_DISCONNECTED) {
            mTitle.setBackgroundColor(Color.parseColor("#FF000000")); //
            setDisconnected(Port.PORT_1);
            setDisconnected(Port.PORT_2);
            updateRemainingPower();
        } else if (hubStatus == HubManager.HUB_STATUS_CONNECTED) {
            mTitle.setBackgroundColor(Color.parseColor("#00FF00")); //green
        } else if (hubStatus == HubManager.HUB_STATUS_ERRORS) {
            mTitle.setBackgroundColor(Color.parseColor("#FF0000")); //red
        }
    }

}
