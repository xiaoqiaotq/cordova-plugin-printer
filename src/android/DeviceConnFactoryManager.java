package org.xiaoqiaotq.cordova.printer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.util.Log;

import com.printer.io.BluetoothPort;
import com.printer.io.EthernetPort;
import com.printer.io.PortManager;
import com.printer.io.SerialPort;
import com.printer.io.UsbPort;

import java.io.IOException;
import java.util.Vector;

import static android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED;

/**
 * Time 2017/8/2
 */
public class DeviceConnFactoryManager {

    public static final byte FLAG = 0x10;
    public static final String ACTION_CONN_STATE = "action_connect_state";
    public static final String ACTION_QUERY_PRINTER_STATE = "action_query_printer_state";
    public static final String STATE = "state";
    public static final String DEVICE_ID = "id";
    public static final int CONN_STATE_DISCONNECT = 0x90;
    public static final int CONN_STATE_CONNECTING = CONN_STATE_DISCONNECT << 1;
    public static final int CONN_STATE_FAILED = CONN_STATE_DISCONNECT << 2;
    public static final int CONN_STATE_CONNECTED = CONN_STATE_DISCONNECT << 3;
    private static final String TAG = DeviceConnFactoryManager.class.getSimpleName();
    /**
     * ESC查询打印机实时状态 缺纸状态
     */
    private static final int ESC_STATE_PAPER_ERR = 0x20;
    /**
     * ESC指令查询打印机实时状态 打印机开盖状态
     */
    private static final int ESC_STATE_COVER_OPEN = 0x04;
    /**
     * ESC指令查询打印机实时状态 打印机报错状态
     */
    private static final int ESC_STATE_ERR_OCCURS = 0x40;
    /**
     * ESC低电量
     */
    private static final int ESC_LOW_POWER = 0x31;
    /**
     * ESC中电量
     */
    private static final int ESC_AMID_POWER = 0x32;
    /**
     * ESC高电量
     */
    private static final int ESC_HIGH_POWER = 0x33;
    /**
     * ESC正在充电
     */
    private static final int ESC_CHARGING = 0x35;
    /**
     * TSC指令查询打印机实时状态 打印机缺纸状态
     */
    private static final int TSC_STATE_PAPER_ERR = 0x04;
    /**
     * TSC指令查询打印机实时状态 打印机开盖状态
     */
    private static final int TSC_STATE_COVER_OPEN = 0x01;
    /**
     * TSC指令查询打印机实时状态 打印机出错状态
     */
    private static final int TSC_STATE_ERR_OCCURS = 0x80;
    /**
     * CPCL指令查询打印机实时状态 打印机缺纸状态
     */
    private static final int CPCL_STATE_PAPER_ERR = 0x01;
    /**
     * CPCL指令查询打印机实时状态 打印机开盖状态
     */
    private static final int CPCL_STATE_COVER_OPEN = 0x02;
    private static final int READ_DATA = 10000;
    private static final String READ_DATA_CNT = "read_data_cnt";
    private static final String READ_BUFFER_ARRAY = "read_buffer_array";
    public static boolean whichFlag = true;
    private static DeviceConnFactoryManager[] deviceConnFactoryManagers = new DeviceConnFactoryManager[4];
    public PortManager mPort;
    public CONN_METHOD connMethod;
    private String ip;
    private int port;
    private String macAddress;
    private UsbDevice mUsbDevice;
    private Context mContext;
    private String serialPortPath;
    private int baudrate;
    private int id;

    BroadcastReceiver usbStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_DEVICE_DETACHED.equals(action)) {
                sendStateBroadcast(CONN_STATE_DISCONNECT);
            }
        }
    };

    private boolean isOpenPort;
    /**
     * ESC查询打印机实时状态指令
     */
    private byte[] esc = {0x10, 0x04, 0x02};
    /**
     * TSC查询打印机状态指令
     */
    private byte[] tsc = {0x1b, '!', '?'};
    private byte[] cpcl = {0x1b, 0x68};
    private byte[] sendCommand;
    /**
     * 判断打印机所使用指令是否是ESC指令
     */
    private PrinterCommand currentPrinterCommand;


    private DeviceConnFactoryManager(Build build) {
        this.connMethod = build.connMethod;
        this.macAddress = build.macAddress;
        this.port = build.port;
        this.ip = build.ip;
        this.mUsbDevice = build.usbDevice;
        this.mContext = build.context;
        this.serialPortPath = build.serialPortPath;
        this.baudrate = build.baudrate;
        this.id = build.id;
        this.currentPrinterCommand = build.printerCommand;
        deviceConnFactoryManagers[id] = this;
    }

    public static DeviceConnFactoryManager[] getDeviceConnFactoryManagers() {
        return deviceConnFactoryManagers;
    }

    public static void closeAllPort() {
        for (DeviceConnFactoryManager deviceConnFactoryManager : deviceConnFactoryManagers) {
            if (deviceConnFactoryManager != null) {
                Log.e(TAG, "cloaseAllPort() id -> " + deviceConnFactoryManager.id);
                deviceConnFactoryManager.closePort(deviceConnFactoryManager.id);
                deviceConnFactoryManagers[deviceConnFactoryManager.id] = null;
            }
        }
    }

    /**
     * 关闭端口（断开连接）
     */
    public void closePort(int id) {
        if (this.mPort != null) {
            System.out.println("id -> " + id);
//            reader.cancel();
            boolean b = this.mPort.closePort();
            if (b) {
                this.mPort = null;
                isOpenPort = false;
                currentPrinterCommand = null;
            }
        }
        sendStateBroadcast(CONN_STATE_DISCONNECT);
    }

    /**
     * 打开端口（开始连接）
     */
    public void openPort() {
        deviceConnFactoryManagers[id].isOpenPort = false;
        sendStateBroadcast(CONN_STATE_CONNECTING);
        switch (deviceConnFactoryManagers[id].connMethod) {
            case BLUETOOTH:
                System.out.println("id -> " + id);
                mPort = new BluetoothPort(macAddress);
                isOpenPort = deviceConnFactoryManagers[id].mPort.openPort();
                break;
            case USB:
                mPort = new UsbPort(mContext, mUsbDevice);
                isOpenPort = mPort.openPort();
                if (isOpenPort) {
                    IntentFilter filter = new IntentFilter(ACTION_USB_DEVICE_DETACHED);
                    mContext.registerReceiver(usbStateReceiver, filter);
                }
                break;
            case WIFI:
                mPort = new EthernetPort(ip, port);
                isOpenPort = mPort.openPort();
                break;
            case SERIAL_PORT:
                mPort = new SerialPort(serialPortPath, baudrate, 0);
                isOpenPort = mPort.openPort();
                break;
            default:
                break;
        }

        //端口打开成功后，检查连接打印机所使用的打印机指令ESC、TSC
        if (isOpenPort) {
            queryCommand();
        } else {
            if (this.mPort != null) {
                this.mPort = null;
            }
            sendStateBroadcast(CONN_STATE_FAILED);
        }
    }

    private void sendStateBroadcast(int state) {
        Intent intent = new Intent(ACTION_CONN_STATE);
        intent.putExtra(STATE, state);
        intent.putExtra(DEVICE_ID, id);
        App.getContext().sendBroadcast(intent);
    }

    /**
     * 查询当前连接打印机所使用打印机指令（ESC（EscCommand.java）、TSC（LabelCommand.java））
     */
    private void queryCommand() {
//        //开启读取打印机返回数据线程
//        reader = new PrinterReader();
//        reader.start(); //读取数据线程
//        //查询打印机所使用指令
//        queryPrinterCommand(); //

//        currentPrinterCommand = PrinterCommand.ESC;
        sendStateBroadcast(CONN_STATE_CONNECTED);
    }


    public void sendDataImmediately(final Vector<Byte> data) {
        if (this.mPort == null) {
            return;
        }
        try {
            this.mPort.writeDataImmediately(data, 0, data.size());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 获取端口连接方式
     *
     * @return
     */
    public CONN_METHOD getConnMethod() {
        return connMethod;
    }

    /**
     * 获取端口打开状态（true 打开，false 未打开）
     *
     * @return
     */
    public boolean getConnState() {
        return isOpenPort;
    }

    /**
     * 获取连接蓝牙的物理地址
     *
     * @return
     */
    public String getMacAddress() {
        return macAddress;
    }

    /**
     * 获取连接网口端口号
     *
     * @return
     */
    public int getPort() {
        return port;
    }

    /**
     * 获取连接网口的IP
     *
     * @return
     */
    public String getIp() {
        return ip;
    }

    /**
     * 获取连接的USB设备信息
     *
     * @return
     */
    public UsbDevice usbDevice() {
        return mUsbDevice;
    }

    /**
     * 获取串口号
     *
     * @return
     */
    public String getSerialPortPath() {
        return serialPortPath;
    }

    /**
     * 获取波特率
     *
     * @return
     */
    public int getBaudrate() {
        return baudrate;
    }

    /**
     * 获取当前打印机指令
     *
     * @return PrinterCommand
     */
    public PrinterCommand getCurrentPrinterCommand() {
        return deviceConnFactoryManagers[id].currentPrinterCommand;
    }

    public int readDataImmediately(byte[] buffer) throws IOException {
        return this.mPort.readData(buffer);
    }

    /**
     * 判断是实时状态（10 04 02）还是查询状态（1D 72 01）
     */
    private int judgeResponseType(byte r) {
        return (byte) ((r & FLAG) >> 4);
    }

    /**
     * 几种连接方式
     */
    public enum CONN_METHOD {
        //蓝牙连接
        BLUETOOTH("BLUETOOTH"),
        //USB连接
        USB("USB"),
        //wifi连接
        WIFI("WIFI"),
        //串口连接
        SERIAL_PORT("SERIAL_PORT");

        private String name;

        private CONN_METHOD(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return this.name;
        }
    }

    /**
     * 使用构建者模式传入数据，清晰 解耦
     */
    public static final class Build {
        private String ip;
        private String macAddress;
        private UsbDevice usbDevice;
        private int port;
        private CONN_METHOD connMethod;
        private Context context;
        private String serialPortPath;
        private int baudrate;
        private int id;//设置标识符
        private PrinterCommand printerCommand;

        public DeviceConnFactoryManager.Build setIp(String ip) {
            this.ip = ip;
            return this;
        }

        public DeviceConnFactoryManager.Build setMacAddress(String macAddress) {
            this.macAddress = macAddress;
            return this;
        }

        public DeviceConnFactoryManager.Build setUsbDevice(UsbDevice usbDevice) {
            this.usbDevice = usbDevice;
            return this;
        }

        public DeviceConnFactoryManager.Build setPort(int port) {
            this.port = port;
            return this;
        }

        public DeviceConnFactoryManager.Build setConnMethod(CONN_METHOD connMethod) {
            this.connMethod = connMethod;
            return this;
        }

        public DeviceConnFactoryManager.Build setContext(Context context) {
            this.context = context;
            return this;
        }

        public DeviceConnFactoryManager.Build setId(int id) {
            this.id = id;
            return this;
        }

        public DeviceConnFactoryManager.Build setSerialPort(String serialPortPath) {
            this.serialPortPath = serialPortPath;
            return this;
        }

        public DeviceConnFactoryManager.Build setBaudrate(int baudrate) {
            this.baudrate = baudrate;
            return this;
        }


        public DeviceConnFactoryManager.Build setPrinterCommand(PrinterCommand command) {
            this.printerCommand = command;
            return this;
        }

        public DeviceConnFactoryManager build() {
            return new DeviceConnFactoryManager(this);
        }
    }

}