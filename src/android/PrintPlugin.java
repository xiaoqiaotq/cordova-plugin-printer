package org.xiaoqiaotq.cordova.printer;

import static android.app.Activity.RESULT_OK;
import static android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Message;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.printer.command.EscCommand;
import com.printer.command.LabelCommand;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.Vector;

public class PrintPlugin extends CordovaPlugin {

    /**
     * 权限请求码
     */
    private static final int REQUEST_CODE = 0x001;

    /**
     * 蓝牙所需权限
     */
    private String[] permissions = {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.BLUETOOTH
    };

    /**
     * 未授予的权限
     */
    private ArrayList<String> per = new ArrayList<>();

    /**
     * 蓝牙请求码
     */
    public static final int BLUETOOTH_REQUEST_CODE = 0x002;


    /**
     * 判断打印机所使用指令是否是ESC指令
     */
    private int id = 0;

    /**
     * 打印机是否连接
     */
    private static final int CONN_PRINTER = 0x003;
    /**
     * 使用打印机指令错误
     */
    private static final int PRINTER_COMMAND_ERROR = 0x004;

    /**
     * 连接状态断开
     */
    private static final int CONN_STATE_DISCONN = 0x005;

    String printerCommand;

    private void checkPermission() {
        for (String permission : permissions) {
            if (PackageManager.PERMISSION_GRANTED !=  cordova.getContext().checkSelfPermission( permission)) {
                per.add(permission);
            }
        }
    }

    private void requestPermission() {
        if (per.size() > 0) {
            String[] p = new String[per.size()];
            this.cordova.requestPermissions( this,REQUEST_CODE,per.toArray(p) );
        }
    }

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
      if (action.equals("print")) {
        byte[] message = (byte[]) args.get(0);
        printerCommand = args.getString(1);

        //先判断打印机是否连接
        if (DeviceConnFactoryManager.getDeviceConnFactoryManagers()[id] == null ||
          !DeviceConnFactoryManager.getDeviceConnFactoryManagers()[id].getConnState()) {
          Toast.makeText(cordova.getContext(), "请先连接打印机", Toast.LENGTH_SHORT).show();
          chooseBluetooth();
        }else {
          print(message);
        }
        return true;
      }
      return false;
    }


    public void chooseBluetooth() {
      cordova.startActivityForResult(this,new Intent(cordova.getContext(), BluetoothListActivity.class), BLUETOOTH_REQUEST_CODE);
    }

  public void print(final byte[] printData) {
    Log.i("TAG", "准备打印");
    cordova.getThreadPool().submit(new Runnable() {
      @Override
      public void run() {
        /* 发送数据 */
        Vector<Byte> data = byteToVector(printData);
        DeviceConnFactoryManager.getDeviceConnFactoryManagers()[id].sendDataImmediately(data);
      }
    });
  }

  public Vector<Byte> byteToVector(byte[] bytes){
    Vector<Byte> vector = new Vector<>(bytes.length);
    for (byte b :bytes){
      vector.add(b);
    }
    return vector;
  }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK) {
            //蓝牙连接
            if (requestCode == BLUETOOTH_REQUEST_CODE) {
                closePort();
                //获取蓝牙mac地址
                String macAddress = data.getStringExtra(BluetoothListActivity.EXTRA_DEVICE_ADDRESS);
                connectBluetooth(macAddress);
            }
        }
    }

  private void connectBluetooth(String macAddress) {
    //初始化DeviceConnFactoryManager 并设置信息
    new DeviceConnFactoryManager.Build()
            //设置标识符
            .setId(id)
            //设置连接方式
            .setConnMethod(DeviceConnFactoryManager.CONN_METHOD.BLUETOOTH)
            //设置连接的蓝牙mac地址
            .setMacAddress(macAddress)
            .setPrinterCommand(PrinterCommand.valueOf(printerCommand))
            .build();
    //配置完信息，就可以打开端口连接了
    Log.i("TAG", "onActivityResult: 连接蓝牙" + id);
    cordova.getThreadPool().submit(new Runnable() {
        @Override
        public void run() {
            DeviceConnFactoryManager.getDeviceConnFactoryManagers()[id].openPort();
        }
    });
  }

  /**
     * 重新连接回收上次连接的对象，避免内存泄漏
     */
    private void closePort() {
        if (DeviceConnFactoryManager.getDeviceConnFactoryManagers()[id] != null && DeviceConnFactoryManager.getDeviceConnFactoryManagers()[id].mPort != null) {
//            DeviceConnFactoryManager.getDeviceConnFactoryManagers()[id].reader.cancel();
            DeviceConnFactoryManager.getDeviceConnFactoryManagers()[id].mPort.closePort();
            DeviceConnFactoryManager.getDeviceConnFactoryManagers()[id].mPort = null;
        }
    }

    @Override
    public void onStart() {
        super.onStart();

        checkPermission();
        requestPermission();

        /*
         * 注册接收连接状态的广播
         */
        IntentFilter filter = new IntentFilter();
        filter.addAction(DeviceConnFactoryManager.ACTION_QUERY_PRINTER_STATE);
        filter.addAction(DeviceConnFactoryManager.ACTION_CONN_STATE);
        cordova.getContext().registerReceiver(receiver, filter);
    }

    @Override
    public void onStop() {
        super.onStop();
        cordova.getContext().unregisterReceiver(receiver);
    }

    /**
     * 连接状态的广播
     */
    private BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (DeviceConnFactoryManager.ACTION_CONN_STATE.equals(action)) {
                int state = intent.getIntExtra(DeviceConnFactoryManager.STATE, -1);
                int deviceId = intent.getIntExtra(DeviceConnFactoryManager.DEVICE_ID, -1);
                switch (state) {
                    case DeviceConnFactoryManager.CONN_STATE_DISCONNECT:
                        break;
                    case DeviceConnFactoryManager.CONN_STATE_CONNECTING:
                        break;
                    case DeviceConnFactoryManager.CONN_STATE_CONNECTED:
                        Toast.makeText(cordova.getContext(), "已连接", Toast.LENGTH_SHORT).show();
                        break;
                    case DeviceConnFactoryManager.CONN_STATE_FAILED:
                        Toast.makeText(cordova.getContext(), "连接失败！重试或重启打印机试试", Toast.LENGTH_SHORT).show();
                        break;
                }
                /* Usb连接断开、蓝牙连接断开广播 */
            } else if (ACTION_USB_DEVICE_DETACHED.equals(action)) {
                mHandler.obtainMessage(CONN_STATE_DISCONN).sendToTarget();
            }
        }
    };

    @SuppressLint("HandlerLeak")
    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case CONN_STATE_DISCONN:
                    if (DeviceConnFactoryManager.getDeviceConnFactoryManagers()[id] != null || !DeviceConnFactoryManager.getDeviceConnFactoryManagers()[id].getConnState()) {
                        DeviceConnFactoryManager.getDeviceConnFactoryManagers()[id].closePort(id);
                        Toast.makeText(cordova.getContext(), "成功断开连接", Toast.LENGTH_SHORT).show();
                    }
                    break;
                case PRINTER_COMMAND_ERROR:
                    Toast.makeText(cordova.getContext(), "请选择正确的打印机指令", Toast.LENGTH_SHORT).show();
                    break;
                case CONN_PRINTER:
                    Toast.makeText(cordova.getContext(), "请先连接打印机", Toast.LENGTH_SHORT).show();
                    break;
            }
        }
    };



    /**
     * 断开连接
     */
    public void btnDisConn(View view) {
        if (DeviceConnFactoryManager.getDeviceConnFactoryManagers()[id] == null ||
                !DeviceConnFactoryManager.getDeviceConnFactoryManagers()[id].getConnState()) {
            Toast.makeText(cordova.getContext(), "请先连接打印机", Toast.LENGTH_SHORT).show();
            return;
        }
        mHandler.obtainMessage(CONN_STATE_DISCONN).sendToTarget();
    }


}
