package com.gromaudio.powerbalancing;

import android.os.Bundle;
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

public class MainActivity extends AppCompatActivity {
    private static final float MAX_POWER = 60;
    private static final float MAX_TOTAL_POWER = 100;

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


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ButterKnife.bind(this);
        init();
    }

    void init() {
        setThermalState(Port.PORT_1, ThermalState.NORMAL);
        setThermalState(Port.PORT_2, ThermalState.NORMAL);
        setDisconnected(Port.PORT_1);
        setDisconnected(Port.PORT_2);

        mMaximumTotalSystemPower.setText(getString(R.string.maximum_total_system_power, MAX_TOTAL_POWER));
        mRemainingTotalSystemPower.setText(getString(R.string.maximum_total_system_power, MAX_TOTAL_POWER));
    }

    void setThermalState(Port port, ThermalState thermalState) {
        TextView portThermalState = port == Port.PORT_1 ? mPort1ThermalState : mPort2ThermalState;
        portThermalState.setBackgroundResource(thermalState.getColor());
        portThermalState.setText(thermalState.getTitle());
    }

    void setDisconnected(Port port) {
        View hide = port == Port.PORT_1 ? mPort1Connected : mPort2Connected;
        View show = port == Port.PORT_1 ? mPort1NoDeviceConnected : mPort2NoDeviceConnected;
        hide.setVisibility(View.INVISIBLE);
        show.setVisibility(View.VISIBLE);
        setProgress(port, 0);
        setThermalState(port, ThermalState.NORMAL);
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

        mRemainingTotalSystemPower.setText(getString(R.string.maximum_total_system_power,
                MAX_TOTAL_POWER - mPort1.getW() - mPort2.getW()));
    }

    float randomFloatInRange(float min, float max) {
        return new Random().nextFloat() * (max - min) + min;
    }
}
