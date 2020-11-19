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
import java.util.HashMap;
import java.util.Map;

import static android.hardware.usb.UsbConstants.USB_DIR_IN;
import static android.hardware.usb.UsbConstants.USB_TYPE_VENDOR;
import static android.hardware.usb.UsbManager.EXTRA_DEVICE;

public class HubManager {
    private static final String TAG = "PB:HubManager";
    private static final boolean DEBUG = true;
    private static final boolean DEBUG_V = DEBUG && true;
    private static final boolean DEBUG_EMULATE_DATA = false;

    public static final int HUB_STATUS_DISCONNECTED = 0;
    public static final int HUB_STATUS_CONNECTED = 1;
    public static final int HUB_STATUS_ERRORS = -1;

    private static final String ACTION_USB_PERMISSION = "com.gromaudio.powerbalancing.USB_PERMISSION";

    private static final int HFC_VID = 0x0424; // (hfc 0x0424); // (mouse 0x046d)
    private static final int HFC_PID = 0x49a0; // (hfc 0x49a0); // (mouse 0xc534)

    private static final int PDPB_BASE_ADDR = 0xBFD245AC;  // address to first byte of PDPB status registers
    private static final int PDPB_BLOCK_RD_LEN = 24;  // 24 bytes defined in PDPB register spec

    //RawData indexes
    private static final int IND_SYS_MAX_P = 0x00;
    private static final int IND_GUAR_MIN_P = 0x02;
    private static final int IND_P1_MAX_P = 0x04;
    private static final int IND_P2_MAX_P = 0x06;
    private static final int IND_P1_STATUS = 0x08;
    private static final int IND_P2_STATUS = 0x09;
    private static final int IND_SHARED_P_CAP = 0x0A;
    private static final int IND_P1_ALLOC_P = 0x0C;
    private static final int IND_P1_NEGOT_V = 0x0E;
    private static final int IND_P1_NEGOT_I = 0x10;
    private static final int IND_P2_ALLOC_P = 0x12;
    private static final int IND_P2_NEGOT_V = 0x14;
    private static final int IND_P2_NEGOT_I = 0x16;

    private static final float WATTS_K = 0.25f;
    private static final float VOLTS_K = 0.05f;
    private static final float AMPS_K = 0.01f;

    // 0010 A0E9 20ED 44C8
    // 9C FC F483 8D6E C00C
    // E9F7 0477 E030 0200

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

    private byte[] mBuff = new byte[1024];
    private byte[] mBuff2 = new byte[1024]; //for debugging
    private int mControlTransferAttempts = CONTROL_TRANSFER_ATTEMPTS;

    public interface IHubListener {
        void onPortStatus(int port, boolean attached, boolean negotiated, boolean orientation, boolean cap_mismatch,
                          float allocpower, float voltage, float current, float power, float pwr_cap);
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
                if (vid==HFC_VID && pid==HFC_PID) {
                    //Request permissions
                    PendingIntent permissionIntent = PendingIntent.getBroadcast(mContext, 0, new Intent(ACTION_USB_PERMISSION), 0);
                    mUsbManager.requestPermission(dev, permissionIntent);
                    Log.d(TAG, "Found HFC and request permissions for: " + entry.getKey() + " ("+vid+":"+pid+")");
                } else {
                    Log.d(TAG, "Found UsbDevice: " + entry.getKey() + " ("+vid+":"+pid+")");
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

    private boolean updateHfcData() {
        int dwAddress = PDPB_BASE_ADDR; //0xBF803000;
        int res = mHfcConnection.controlTransfer(
                USB_DIR_IN | USB_TYPE_VENDOR, //RequestType - 0xC0 (0x80 | 0x40 | 0x00 )
                CMD_MEMORY_READ,                         //Request - 0x04
                (dwAddress & 0xFFFF),                    //wValue
                ((dwAddress & 0xFFFF0000) >> 16),        //wIndex
                mBuff,                                    //Data
                PDPB_BLOCK_RD_LEN,                       //wLength  (bytes to read)
                CTRL_TIMEOUT                             //timeout ms.
                );
        if (res >= 0) {
            if (DEBUG) {
                Log.d(TAG, "controlTransfer success: " + res);
                Log.d(TAG, "controlTransfer data: " + bytesToHex(mBuff, PDPB_BLOCK_RD_LEN));
            }


            //test
            int dwAddress2 = 0xBF80_3000; //length 2
            int res2 = mHfcConnection.controlTransfer(
                    USB_DIR_IN | USB_TYPE_VENDOR, //RequestType - 0xC0 (0x80 | 0x40 | 0x00 )
                    CMD_MEMORY_READ,                         //Request - 0x04
                    (dwAddress2 & 0xFFFF),                    //wValue
                    ((dwAddress2 & 0xFFFF0000) >> 16),        //wIndex
                    mBuff2,                                    //Data
                    4,                             //wLength  (bytes to read)
                    CTRL_TIMEOUT                             //timeout ms.
            );
            Log.d(TAG, "controlTransfer2 success: " + ((dwAddress2 & 0xFFFF)& 0x0000FFFF) );
            Log.d(TAG, "controlTransfer2 success: " + (((dwAddress2 & 0xFFFF0000) >> 16) & 0x0000FFFF) );
            Log.d(TAG, "controlTransfer2 success: " + res2);
            Log.d(TAG, "controlTransfer2 data: " + bytesToHex(mBuff2, 4));


            mControlTransferAttempts = CONTROL_TRANSFER_ATTEMPTS;
            parseHfcData(mBuff, res);
            return true;
        } else {
            Log.e(TAG, "controlTransfer error: " + res + "; "+(--mControlTransferAttempts)+" attempts left.");
            if (mListener!=null) {
                mListener.onHubStatus(HUB_STATUS_ERRORS);
            }

            //emulate data for debugging
            //0010A0E920ED44C89CFCF4838D6EC00CE9F70477E0300200
            if (DEBUG_EMULATE_DATA) {
                byte[] emulated = {
                        (byte)0x00,(byte)0x10,(byte)0xA0,(byte)0xE9,(byte)0x20,(byte)0xED,(byte)0x44,(byte)0xC8,
                        (byte)0x01,(byte)0x01/*(byte)0x9C,(byte)0xFC*/,(byte)0xF4,(byte)0x83,(byte)0x8D,(byte)0x6E,(byte)0xC0,(byte)0x0C,
                        (byte)0xE9,(byte)0xF7,(byte)0x04,(byte)0x77,(byte)0xE0,(byte)0x30,(byte)0x02,(byte)0x00};
                /*byte[] emulated = {
                        0,1,2,3,4,5,6,7,
                        0,1,2,3,4,5,6,7,
                        0,1, 0x00,(byte)0xC8, 4,5,6,7,
                    };*/
                if (DEBUG) {
                    Log.d(TAG, "Emulated data: " + bytesToHex(emulated, PDPB_BLOCK_RD_LEN));
                }
                parseHfcData(emulated, PDPB_BLOCK_RD_LEN);
                return true;
            }

            if (mControlTransferAttempts > 0) {
                return true;
            }
            return false;
        }
    }

    private void parseHfcData(byte[] data, int len) {
        byte raw_status_now1 = data[IND_P1_STATUS];
        byte raw_status_now2 = data[IND_P2_STATUS];
        boolean attached1 = ((raw_status_now1 & 0x01) == 0x01);
        boolean attached2 = ((raw_status_now2 & 0x01) == 0x01);
        boolean cap_mismatch1 = ((raw_status_now1 & 0x20) == 0x20);
        boolean cap_mismatch2 = ((raw_status_now2 & 0x20) == 0x20);
        boolean orientation1 = ((raw_status_now1 & 0x02) == 0x02);
        boolean orientation2 = ((raw_status_now2 & 0x02) == 0x02);
        boolean negotiated1 = ((raw_status_now1 & 0x10) == 0x10);
        boolean negotiated2 = ((raw_status_now2 & 0x10) == 0x10);

        ByteBuffer bb = ByteBuffer.wrap(data);
        int alloc_pwr_now1 = (0xFFFF & bb.getShort(IND_P1_ALLOC_P)); // 0.25W/250mW units
        int alloc_pwr_now2 = (0xFFFF & bb.getShort(IND_P2_ALLOC_P)); // 0.25W/250mW units
        int negot_v_now1 = (0xFFFF & bb.getShort(IND_P1_NEGOT_V)); // 0.05V/50mV units
        int negot_v_now2 = (0xFFFF & bb.getShort(IND_P2_NEGOT_V)); // 0.05V/50mV units
        int negot_i_now1 = (0xFFFF & bb.getShort(IND_P1_NEGOT_I)); // 0.01A/10mA units
        int negot_i_now2 = (0xFFFF & bb.getShort(IND_P2_NEGOT_I)); // 0.01A/10mA units
        int shared_pwr_cap = (0xFFFF & bb.getShort(IND_SHARED_P_CAP)); // 0.25W/250mW units

        /**
         * Fixes.
         * Need to tidy up the values in case of USB-C (non-PD) attach
         * Cosmetic: The power balancing algorithm does not zero the previously-negotiated V & I upon
         * detach which makes new USB-C (non-PD) connections take the previous negotiated power value in UI.
         * Workaround: If attached but not "negotiated", set V=5V and I=3A (this may not accurately
         * reflect the true VBUS state as "negotiated" is a context of the power balancing algorithm
         * and not any explicit PD contract).
        */
        if (attached1 && !negotiated1) {
            negot_v_now1 = (int)(5/VOLTS_K);
            negot_i_now1 = (int)(3/AMPS_K);
        }
        if (attached2 && !negotiated2) {
            negot_v_now2 = (int)(5/VOLTS_K);
            negot_i_now2 = (int)(3/AMPS_K);
        }

        if (mListener!=null) {
            mListener.onPortStatus(1, attached1, negotiated1, orientation1, cap_mismatch1,
                    (float)(alloc_pwr_now1*WATTS_K),
                    (float)(negot_v_now1*VOLTS_K),
                    (float)(negot_i_now1*AMPS_K),
                    (float)((negot_v_now1*VOLTS_K)*(negot_i_now1*AMPS_K)),
                    (float)(shared_pwr_cap*WATTS_K) );
            mListener.onPortStatus(2, attached2, negotiated2, orientation2, cap_mismatch2,
                    (float)(alloc_pwr_now2*WATTS_K),
                    (float)(negot_v_now2*VOLTS_K),
                    (float)(negot_i_now2*AMPS_K),
                    (float)((negot_v_now2*VOLTS_K)*(negot_i_now2*AMPS_K)),
                    (float)(shared_pwr_cap*WATTS_K) );
        }
        if (DEBUG_V) {
            //Log Port1
            Log.d(TAG, "----------------------------------------");
            Log.d(TAG, String.format("Port1: allocpower=%f", alloc_pwr_now1*WATTS_K));
            Log.d(TAG, String.format("Port1: voltage=%f", negot_v_now1*VOLTS_K));
            Log.d(TAG, String.format("Port1: current=%f", negot_i_now1*AMPS_K));
            Log.d(TAG, String.format("Port1: power=%f", (negot_v_now1*VOLTS_K) * (negot_i_now1*AMPS_K)));
            Log.d(TAG, String.format("Port1: shared_pwr_cap=%f", shared_pwr_cap*WATTS_K));
            Log.d(TAG, String.format("Port1: attached=%s", attached1));
            Log.d(TAG, String.format("Port1: negotiated=%s", negotiated1));
            Log.d(TAG, String.format("Port1: orientation=%s", orientation1));
            Log.d(TAG, String.format("Port1: cap_mismatch=%s", cap_mismatch1));
            //Log Port2
            Log.d(TAG, "----------------------------------------");
            Log.d(TAG, String.format("Port2: allocpower=%f", alloc_pwr_now2*WATTS_K));
            Log.d(TAG, String.format("Port2: voltage=%f", negot_v_now2*VOLTS_K));
            Log.d(TAG, String.format("Port2: current=%f", negot_i_now2*AMPS_K));
            Log.d(TAG, String.format("Port2: power=%f", (negot_v_now2*VOLTS_K) * (negot_i_now2*AMPS_K)));
            Log.d(TAG, String.format("Port2: shared_pwr_cap=%f", shared_pwr_cap*WATTS_K));
            Log.d(TAG, String.format("Port2: attached=%s", attached2));
            Log.d(TAG, String.format("Port2: negotiated=%s", negotiated2));
            Log.d(TAG, String.format("Port2: orientation=%s", orientation2));
            Log.d(TAG, String.format("Port2: cap_mismatch=%s", cap_mismatch2));
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
            if (vid==HFC_VID && pid==HFC_PID) {
                return true;
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

