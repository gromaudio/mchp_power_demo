package com.gromaudio.powerbalancing;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

import static android.hardware.usb.UsbConstants.USB_DIR_IN;
import static android.hardware.usb.UsbConstants.USB_TYPE_VENDOR;
import static android.hardware.usb.UsbManager.EXTRA_DEVICE;

public class HubManager {
    private static final String TAG = "PB:HubManager";
    private static final boolean DEBUG = true;
    private static final boolean DEBUG_V = DEBUG && true;

    public static final int HUB_STATUS_DISCONNECTED = 0;
    public static final int HUB_STATUS_CONNECTED = 1;
    public static final int HUB_STATUS_ERRORS = -1;

    private static final String ACTION_USB_PERMISSION = "com.gromaudio.powerbalancing.USB_PERMISSION";

    //Lookup table.
    class HubId {
        HubId(int vid, int pid) {VID=vid; PID=pid;}
        int VID;
        int PID;
    };
    //Don't forget to add the new VID:PID to "res/xml/device_filter.xml" as well.
    private HubId[] mLookupTable = { new HubId(0x0424,0x49a0),
                                     new HubId(0x046d,0xc534), //just for debugging
                                   };


    static final int PDPB_P1_THERMAL_PORT_STATUS = 0xBFD9_7444; //2 Bytes (THERMAL_STATE)
    static final int PDPB_P3_THERMAL_PORT_STATUS = 0xBFD9_74DC; //2 Bytes (THERMAL_STATE)
    static final int PDPB_P1_PORT_PARAMS = 0xBFD9_7BC0; // 00=xx (Offset = 0x04) 8 Bytes (7BC0)
    static final int PDPB_P3_PORT_PARAMS = 0xBFD9_7D74; // 00=xx (Offset = 0x04) 8 Bytes (7D74)
    static final int PDPB_P1_PORT_POWER_ALLOCATION = 0xBFD9_7E28; //4 Bytes (PB enabled/disabled, port MAX power)
    static final int PDPB_P3_PORT_POWER_ALLOCATION = 0xBFD9_7E6C; //4 Bytes (PB enabled/disabled, port MAX power)
    static final int PDPB_PB_SYS_CONFIG = 0xBFD9_7DE4; //4 Bytes (Total system power)

    class PortBuffers {
        byte[] mPortStatusBuff = new byte[32];
        byte[] mPortParamsBuff = new byte[32];
        byte[] mPortPowerBuff = new byte[32];
    };

    private static final float SYS_WATTS_K = 1000.0f;
    private static final float PORT_WATTS_K = 0.5f;
    private static final float VOLTS_K = 0.05f;
    private static final float VOLTS_OPERATE_K = 0.02f;
    //*0.5f (Connor: The current reading is off. It is twice would it should be. Just divide the current current reading by 2.)
    private static final float AMPS_K = 0.01f *0.5f;

    private static final float DEFAULT_V = 5.0f; // 5.0V
    private static final float DEFAULT_I = 3.0f; // 3.0A

    //Usb ControlTransfer request codes
    private static final int CMD_MEMORY_READ = 0x04;
    private static final int CMD_MEMORY_WRITE = 0x03;

    private static final int CTRL_TIMEOUT = 5*1000;  //ms
    private static final int DATA_UPDATE_PERIOD = 1000; //ms
    private static final int CONTROL_TRANSFER_ATTEMPTS = 5;

    private UsbManager mUsbManager;
    private UsbDevice mHfcDevice; //Hub feature controller
    private UsbDeviceConnection mHfcConnection;
    private Context mContext;
    private Handler mHandler;
    private IHubListener mListener;

    private PortBuffers mP1Buffs = new PortBuffers();
    private PortBuffers mP3Buffs = new PortBuffers();
    private byte[] mSysConfBuff = new byte[32];

    private int mControlTransferAttempts = CONTROL_TRANSFER_ATTEMPTS;

    public interface IHubListener {
        void onPortStatus(int port, boolean attached, boolean negotiated, boolean orientation, boolean cap_mismatch,
                          float maxpower, float voltage, float current, float power, float sys_pwr, ThermalState ts);
        void onHubStatus(int hubStatus);
    }

    public HubManager(Context ctx, IHubListener listener) {
        mContext = ctx;
        mListener = listener;
        mHandler = new Handler();

        mUsbManager = (UsbManager) mContext.getSystemService(Context.USB_SERVICE);
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        mContext.registerReceiver(mUsbReceiver, filter);
        if (mListener!=null) {
            mListener.onHubStatus(HUB_STATUS_DISCONNECTED);
        }
    }

    public void close() {
        mContext.unregisterReceiver(mUsbReceiver);
        mUsbManager = null;
    }

    public void update() {
        Log.d(TAG, "update()");
        findHfc();
    }

    public void stop() {
        Log.d(TAG, "stop()");
        mHandler.removeCallbacks(mDataUpdater);
        if (mHfcConnection != null) {
            mHfcConnection.close();
            mHfcConnection = null;
        }
        mHfcDevice = null;
        if (mListener!=null) {
            mListener.onHubStatus(HUB_STATUS_DISCONNECTED);
        }
    }

    private void findHfc() {
        if (mUsbManager==null) return;
        HashMap<String, UsbDevice> devices = mUsbManager.getDeviceList();
        for(Map.Entry<String, UsbDevice> entry : devices.entrySet()) {
            UsbDevice dev = entry.getValue();
            if (dev != null) {
                int vid = dev.getVendorId();
                int pid = dev.getProductId();
                Log.d(TAG, String.format("Found UsbDevice: %s (%04x:%04x)", entry.getKey(), vid,pid));
                for (int a=0; a < mLookupTable.length; ++a) {
                    if (vid==mLookupTable[a].VID && pid==mLookupTable[a].PID) {
                        //Request permissions
                        PendingIntent permissionIntent = PendingIntent.getBroadcast(mContext, 0, new Intent(ACTION_USB_PERMISSION), 0);
                        mUsbManager.requestPermission(dev, permissionIntent);
                        Log.d(TAG, String.format("Found HFC and request permissions for: %s (%04x:%04x)", entry.getKey(), vid,pid));
                        break;
                    }
                }
            }
        }
    }

    private void connectHfc() {
        if (mUsbManager!=null && mHfcDevice != null) {
            if (mHfcConnection!=null) {
                mHfcConnection.close();
            }
            mHfcConnection = mUsbManager.openDevice(mHfcDevice);
            if (mHfcConnection != null) {
                Log.d(TAG, "Start HFC data updating...");
                if (mListener!=null) {
                    mListener.onHubStatus(HUB_STATUS_CONNECTED);
                }
                mControlTransferAttempts = CONTROL_TRANSFER_ATTEMPTS;
                mHandler.removeCallbacks(mDataUpdater);
                mHandler.postDelayed(mDataUpdater, DATA_UPDATE_PERIOD);
            } else {
                Log.e(TAG, "Can't open HFC UsbDevice " + mHfcDevice);
                if (mListener!=null) {
                    mListener.onHubStatus(HUB_STATUS_DISCONNECTED);
                }
            }
        }
    }

    Runnable mDataUpdater = new Runnable() {
        @Override
        public void run() {
            mHandler.removeCallbacks(mDataUpdater);
            if (mHfcDevice != null && mHfcConnection != null) {
                if (updateHfcData()) {
                    mHandler.postDelayed(mDataUpdater, DATA_UPDATE_PERIOD);
                } else {
                    Log.d(TAG, "Hfc don't response. Try to reconnect...");
                    stop();
                    findHfc();
                }
            } else {
                Log.d(TAG, "Stop HFC data updating");
            }
        }
    };

    private boolean getHfcData(int addr, int size, byte[] resData, String name) {
        int res = mHfcConnection.controlTransfer(
                USB_DIR_IN | USB_TYPE_VENDOR, //RequestType - 0xC0 (0x80 | 0x40 | 0x00 )
                CMD_MEMORY_READ,                         //Request - 0x04
                (addr & 0xFFFF),                         //wValue
                ((addr & 0xFFFF0000) >> 16),             //wIndex
                resData,                                 //Data
                size,                                    //wLength  (bytes to read)
                CTRL_TIMEOUT                             //timeout ms.
        );
        if (res >= 0) {
            if (DEBUG) {
                Log.d(TAG, "controlTransfer success ("+res+"): " + bytesToHex(resData, size) + " ("+name+")");
            }
            return true;
        }
        Log.e(TAG, "controlTransfer error: res=" + res + "("+name+")");
        return false;
    }

    private boolean updateHfcData() {
        boolean res = getHfcData(PDPB_P1_THERMAL_PORT_STATUS, 2, mP1Buffs.mPortStatusBuff, "P1_THERMAL_PORT_STATUS");
        if (res) {
            res = getHfcData(PDPB_P3_THERMAL_PORT_STATUS, 2, mP3Buffs.mPortStatusBuff, "P3_THERMAL_PORT_STATUS");
        }
        if (res) {
            res = getHfcData(PDPB_P1_PORT_PARAMS, 8, mP1Buffs.mPortParamsBuff, "P1_PORT_PARAMS");
        }
        if (res) {
            res = getHfcData(PDPB_P3_PORT_PARAMS, 8, mP3Buffs.mPortParamsBuff, "P3_PORT_PARAMS");
        }
        if (res) {
            res = getHfcData(PDPB_P1_PORT_POWER_ALLOCATION, 4, mP1Buffs.mPortPowerBuff, "P1_PORT_POWER_ALLOCATION");
        }
        if (res) {
            res = getHfcData(PDPB_P3_PORT_POWER_ALLOCATION, 4, mP3Buffs.mPortPowerBuff, "P3_PORT_POWER_ALLOCATION");
        }
        if (res) {
            res = getHfcData(PDPB_PB_SYS_CONFIG, 4, mSysConfBuff, "PB_SYS_CONFIG");
        }

        if (res) {
            if (DEBUG) {
                Log.d(TAG, "updateHfcData() success.");
            }
            mControlTransferAttempts = CONTROL_TRANSFER_ATTEMPTS;
            parseHfcData(mSysConfBuff, mP1Buffs, mP3Buffs);
            return true;
        } else {
            Log.e(TAG, "updateHfcData() error: "+(--mControlTransferAttempts)+" attempts left.");
            if (mListener!=null) {
                mListener.onHubStatus(HUB_STATUS_ERRORS);
            }
            if (mControlTransferAttempts > 0) {
                return true;
            }
            return false;
        }
    }

    private ThermalState parseThermalHfcData(byte[] data) {
        ThermalState st = ThermalState.NOT_IMPLEMENTED;
        switch (data[0]&0x03) {
            case 0x00:
                st = ThermalState.NORMAL;
                break;
            case 0x01:
                st = ThermalState.WARNING;
                break;
            case 0x02:
                st = ThermalState.SHUTDOWN;
                break;
        }
        return st;
    }

    private String getRP_RD(byte b) {
        switch (b) {
            case 0x00: return "Default USB";
            case 0x01: return "1.5A";
            case 0x02: return "3.0A";
            default: return "unknown";
        }
    }

    private void parseHfcData(byte[] sysConfBuff, PortBuffers p1, PortBuffers p2) {
        //ThermalStatus
        ThermalState ts1 = parseThermalHfcData(p1.mPortStatusBuff);
        ThermalState ts2 = parseThermalHfcData(p2.mPortStatusBuff);

        //parse params
        boolean attached1 = ((p1.mPortParamsBuff[0] & 0x01) == 0x01);
        boolean attached2 = ((p2.mPortParamsBuff[0] & 0x01) == 0x01);
        boolean orientation1 = ((p1.mPortParamsBuff[0] & 0x02) == 0x02);
        boolean orientation2 = ((p2.mPortParamsBuff[0] & 0x02) == 0x02);

        byte rp_rd1 = (byte)((p1.mPortParamsBuff[0] & 0x0C) >> 2);
        byte rp_rd2 = (byte)((p2.mPortParamsBuff[0] & 0x0C) >> 2);

        boolean negotiated1 = ((p1.mPortParamsBuff[0] & 0x10) == 0x10);
        boolean negotiated2 = ((p2.mPortParamsBuff[0] & 0x10) == 0x10);
        boolean cap_mismatch1 = ((p1.mPortParamsBuff[0] & 0x20) == 0x20);
        boolean cap_mismatch2 = ((p2.mPortParamsBuff[0] & 0x20) == 0x20);
        boolean contract_operate1 = ((p1.mPortParamsBuff[7] & 0x40) == 0x40);
        boolean contract_operate2 = ((p2.mPortParamsBuff[7] & 0x40) == 0x40);

        ByteBuffer bb1 = ByteBuffer.wrap(p1.mPortParamsBuff).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer bb2 = ByteBuffer.wrap(p2.mPortParamsBuff).order(ByteOrder.LITTLE_ENDIAN);
        //V_NEGOTIATED (15:6 bits)
        int negot_v_now1 = ((0xFFC0 & bb1.getShort(0)) >> 6); // 0.05V/50mV units
        int negot_v_now2 = ((0xFFC0 & bb2.getShort(0)) >> 6); // 0.05V/50mV units
        //I_NEGOTIATED (25:16 bits)
        int negot_i_now1 = (0x03FF & bb1.getShort(2)); // 0.01A/10mA units
        int negot_i_now2 = (0x03FF & bb2.getShort(2)); // 0.01A/10mA units
        //V_OPERATIONAL (41:32 bits)
        int operate_v_now1 = (0x03FF & bb1.getShort(4)); // 0.02V/20mV units
        int operate_v_now2 = (0x03FF & bb2.getShort(4)); // 0.02V/20mV units

        //Max system power
        ByteBuffer sc = ByteBuffer.wrap(sysConfBuff).order(ByteOrder.LITTLE_ENDIAN);
        int sys_pwr = (0x00FFFFFF & sc.getInt()); // The max shared power capacity (in mW)

        //Max ports power
        ByteBuffer pp1 = ByteBuffer.wrap(p1.mPortPowerBuff).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer pp2 = ByteBuffer.wrap(p2.mPortPowerBuff).order(ByteOrder.LITTLE_ENDIAN);
        int max_pwr1 = (0x03FF & pp1.getShort(0)); // 0.5W/500mW units
        int max_pwr2 = (0x03FF & pp2.getShort(0)); // 0.5W/500mW units
        boolean pb_enabled1 = ((p1.mPortPowerBuff[3] & 0x08) == 0x08);
        boolean pb_enabled2 = ((p2.mPortPowerBuff[3] & 0x08) == 0x08);

        /**
         * Fixes.
         * Need to tidy up the values in case of USB-C (non-PD) attach
         * Cosmetic: The power balancing algorithm does not zero the previously-negotiated V & I upon
         * detach which makes new USB-C (non-PD) connections take the previous negotiated power value in UI.
         * Workaround: If attached but not "negotiated", set V=5V and I=3A (this may not accurately
         * reflect the true VBUS state as "negotiated" is a context of the power balancing algorithm
         * and not any explicit PD contract).
        */
        float res_v1 = contract_operate1 ? operate_v_now1*VOLTS_OPERATE_K : negot_v_now1*VOLTS_K;
        float res_i1 = negot_i_now1*AMPS_K;
        if (attached1 && !negotiated1) {
            res_v1 = DEFAULT_V;
            res_i1 = DEFAULT_I;
        }
        float res_v2 = contract_operate2 ? operate_v_now2*VOLTS_OPERATE_K : negot_v_now2*VOLTS_K;
        float res_i2 = negot_i_now2*AMPS_K;
        if (attached2 && !negotiated2) {
            res_v2 = DEFAULT_V;
            res_i2 = DEFAULT_I;
        }

        if (mListener!=null) {
            mListener.onPortStatus(1, attached1, negotiated1, orientation1, cap_mismatch1,
                    (float)(max_pwr1*PORT_WATTS_K),
                    res_v1,
                    res_i1,
                    (res_v1 * res_i1),
                    (float)(sys_pwr/SYS_WATTS_K),
                    ts1);
            mListener.onPortStatus(2, attached2, negotiated2, orientation2, cap_mismatch2,
                    (float)(max_pwr2*PORT_WATTS_K),
                    res_v2,
                    res_i2,
                    (res_v2 * res_i2),
                    (float)(sys_pwr/SYS_WATTS_K),
                    ts2);
        }
        if (DEBUG_V) {
            Log.d(TAG, "----------------------------------------");
            Log.d(TAG, String.format("SYS: sys_pwr=%f W", sys_pwr/SYS_WATTS_K));
            //Log Port1
            Log.d(TAG, "----------------------------------------");
            Log.d(TAG, String.format("Port1: max_pwr=%f W", max_pwr1*PORT_WATTS_K));
            Log.d(TAG, String.format("Port1: pb_enabled=%s", pb_enabled1));
            Log.d(TAG, String.format("Port1: voltage_negot=%f", negot_v_now1*VOLTS_K));
            Log.d(TAG, String.format("Port1: voltage_operate=%f", operate_v_now1*VOLTS_OPERATE_K));
            Log.d(TAG, String.format("Port1: current_negot=%f", negot_i_now1*AMPS_K));
            Log.d(TAG, String.format("Port1: volt_res=%f", res_v1));
            Log.d(TAG, String.format("Port1: curr_res=%f", res_i1));
            Log.d(TAG, String.format("Port1: power=%f", (res_v1 * res_i1) ));
            Log.d(TAG, String.format("Port1: attached=%s", attached1));
            Log.d(TAG, String.format("Port1: negotiated=%s", negotiated1));
            Log.d(TAG, String.format("Port1: contract_operate=%s", contract_operate1));
            Log.d(TAG, String.format("Port1: orientation=%s", orientation1));
            Log.d(TAG, String.format("Port1: cap_mismatch=%s", cap_mismatch1));
            Log.d(TAG, String.format("Port1: rp_rd=%s", getRP_RD(rp_rd1)));
            Log.d(TAG, String.format("Port1: ThermalState=%s", ts1));
            //Log Port2
            Log.d(TAG, "----------------------------------------");
            Log.d(TAG, String.format("Port3: max_pwr=%f W", max_pwr2*PORT_WATTS_K));
            Log.d(TAG, String.format("Port3: pb_enabled=%s", pb_enabled2));
            Log.d(TAG, String.format("Port3: voltage_negot=%f", negot_v_now2*VOLTS_K));
            Log.d(TAG, String.format("Port3: voltage_operate=%f", operate_v_now2*VOLTS_OPERATE_K));
            Log.d(TAG, String.format("Port3: current_negot=%f", negot_i_now2*AMPS_K));
            Log.d(TAG, String.format("Port3: volt_res=%f", res_v2));
            Log.d(TAG, String.format("Port3: curr_res=%f", res_i2));
            Log.d(TAG, String.format("Port3: power=%f", (res_v2 * res_i2) ));
            Log.d(TAG, String.format("Port3: attached=%s", attached2));
            Log.d(TAG, String.format("Port3: negotiated=%s", negotiated2));
            Log.d(TAG, String.format("Port3: contract_operate=%s", contract_operate2));
            Log.d(TAG, String.format("Port3: orientation=%s", orientation2));
            Log.d(TAG, String.format("Port3: cap_mismatch=%s", cap_mismatch2));
            Log.d(TAG, String.format("Port3: rp_rd=%s", getRP_RD(rp_rd2)));
            Log.d(TAG, String.format("Port3: ThermalState=%s", ts2));
            Log.d(TAG, "----------------------------------------");
        }
    }


    private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "onReceive("+intent+")");
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbDevice device = (UsbDevice)intent.getParcelableExtra(EXTRA_DEVICE);
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (device != null) {
                            Log.d(TAG, "permission GRANTED for device " + device.getDeviceName());
                            mHfcDevice = device;
                            connectHfc();
                        }
                    }
                    else {
                        Log.d(TAG, "permission denied for device " + device.getDeviceName());
                        mHfcDevice = null;
                        mHfcConnection = null;
                        if (mListener!=null) {
                            mListener.onHubStatus(HUB_STATUS_DISCONNECTED);
                        }
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                UsbDevice device = (UsbDevice)intent.getParcelableExtra(EXTRA_DEVICE);
                if (isHfcDevice(device) && (mHfcDevice==null || mHfcConnection==null) ) {
                    //findHfc();
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice device = (UsbDevice)intent.getParcelableExtra(EXTRA_DEVICE);
                if (isHfcDevice(device)) {
                    stop();
                }
            }
        }
    };

    private boolean isHfcDevice(UsbDevice device) {
        if (device!=null) {
            int vid = device.getVendorId();
            int pid = device.getProductId();
            for (int a=0; a < mLookupTable.length; ++a) {
                if (vid==mLookupTable[a].VID && pid==mLookupTable[a].PID) {
                    return true;
                }
            }
        }
        return false;
    }

    private static final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();
    public static String bytesToHex(byte[] bytes, int len) {
        char[] hexChars = new char[len * 2];
        for (int j = 0; j < len; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars);
    }

}

