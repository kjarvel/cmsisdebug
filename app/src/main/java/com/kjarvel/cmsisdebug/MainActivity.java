/* *************************************************************************
 *   Copyright (C) 2018 Niklas Kallman <kjarvel@gmail.com>                 *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *                                                                         *
 *   This program is distributed in the hope that it will be useful,       *
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of        *
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the         *
 *   GNU General Public License for more details.                          *
 *                                                                         *
 *   You should have received a copy of the GNU General Public License     *
 *   along with this program.  If not, see <http://www.gnu.org/licenses/>. *
 ***************************************************************************/

package com.kjarvel.cmsisdebug;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;

import com.kjarvel.cmsisdebug.R;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.view.Menu;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Main activity.
 * 
 * Handles the graphical part of the Application, and some logic.
 *
 */
public class MainActivity extends Activity {
    private static final String ACTION_USB_PERMISSION =
            "com.android.example.USB_PERMISSION";
    private static final int TMO_MSG = 101;
    private PendingIntent mPermissionIntent;
    private UsbManager mUsbManager;
    private ARMInfo mARMinfo;
    private TextView firmwareText;
    private TextView otherText;
    private TextView regText;
    private ProgressBar progressBar;
    private Resources res;
    private Switch connectSwitch;
    private Button resetButton;
    private Button haltButton;
    private Button goButton;
    private Button readButton;
    private Button writeButton;
    private ArrayList<Button> buttonArr = new ArrayList<>();
    private MsgHandler msgHandler = new MsgHandler(this);

    /**
     * Global init. Registers an USB intent receiver.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        regText = (TextView) findViewById(R.id.regView);
        
        resetButton = (Button) findViewById(R.id.reset_button);
        resetButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                mARMinfo.cpuReset();
                regText.setText("");
            }
        });

        goButton = (Button) findViewById(R.id.goButton);
        goButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                mARMinfo.cpuRun();
                regText.setText("");
            }
        });

        
        haltButton = (Button) findViewById(R.id.haltButton);
        haltButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (mARMinfo.cpuHalt()) {
                    regText.setText(mARMinfo.getCoreRegs());
                }
            }
        });

        
        readButton = (Button) findViewById(R.id.readButton);
        final EditText rV = (EditText) findViewById(R.id.readValue);
        final TextView rA = (TextView) findViewById(R.id.readAddr);
        readButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (rA.length() > 0) {
                    long addr = Long.parseLong(rA.getText().toString(), 16);
                    int value = (int)mARMinfo.readAddr(addr);
                    rV.setText(String.format("%08x", value));
                }
            }
        });
        
        writeButton = (Button) findViewById(R.id.writeButton);
        final EditText wV = (EditText) findViewById(R.id.writeValue);
        writeButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (rA.length() > 0 && wV.length() > 0) {
                    long addr = Long.parseLong(rA.getText().toString(), 16);
                    long value = Long.parseLong(wV.getText().toString(), 16);
                    mARMinfo.writeAddr(addr, value);
                    rV.setText(""); // After write, clear read value
                }
            }
        });
        
        buttonArr.add(resetButton);
        buttonArr.add(goButton);
        buttonArr.add(haltButton);
        buttonArr.add(readButton);
        buttonArr.add(writeButton);
        
        connectSwitch = (Switch) findViewById(R.id.cmsis_switch);
        connectSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton buttonView,
                    boolean isChecked) {
                if (isChecked) {
                    progressBar.setVisibility(ProgressBar.VISIBLE);
                    otherText.setText(R.string.detecting_msg);
                    firmwareText.setText("");
                    msgHandler.sendEmptyMessageDelayed(TMO_MSG, 2000);
                    getUSBPermission();

                } else {
                    mARMinfo.disconnect();
                    for (Button btn: buttonArr) {
                    	btn.setEnabled(false);
                    	btn.setTextColor(res.getColor(android.R.color.darker_gray));
                    }
                }
            }
        });

        firmwareText = (TextView) findViewById(R.id.firmwareText);
        firmwareText.setSingleLine(false);

        otherText = (TextView) findViewById(R.id.otherText);
        progressBar = (ProgressBar) findViewById(R.id.progressBar);
        res = getResources();

        mARMinfo = new ARMInfo(res);

        mPermissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(
                ACTION_USB_PERMISSION), 0);
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        registerReceiver(mUsbReceiver, filter);
    }

    
    /**
     * 
     * Internal class to handle timeout messages.
     * Completely strange code to handle the warning:
     * 'This Handler class should be static or leaks might occur'
     * 
     */
    static class MsgHandler extends Handler {
    	private final WeakReference<MainActivity> mainAct;
    	
    	public MsgHandler(MainActivity m) {
    		mainAct = new WeakReference<>(m);
		}
    	
        @Override
        public void handleMessage(Message msg) {
        	MainActivity m = mainAct.get();
        	if (m != null) {
        		m.handleMessage();
        	}
        }
    	
    }
    
    /**
     * Handles a detect timeout message.
     */
    public void handleMessage() {
        progressBar.setVisibility(ProgressBar.INVISIBLE);
        otherText.setText(R.string.no_dev_detect);
        connectSwitch.setChecked(false);
    }
    
    /**
     * The USB broadcast receiver. This is called when the USB permission is
     * received.
     */
    private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            StringBuffer s = new StringBuffer("");
            StringBuffer t = new StringBuffer("");
            
            if (ACTION_USB_PERMISSION.equals(action) && mUsbManager != null) {
                msgHandler.removeMessages(TMO_MSG);
                progressBar.setVisibility(ProgressBar.INVISIBLE);
                firmwareText.setText("");
                otherText.setText("");
                
                if (mARMinfo.open(intent, mUsbManager, s)) {
                    otherText.setText(s);
                    if (mARMinfo.connect(t)) {
                        firmwareText.setText(t);
                        
                        for (Button btn: buttonArr) {
                        	btn.setEnabled(true);
                        	btn.setTextColor(res.getColor(android.R.color.holo_blue_light));
                        }
                    } else {
                        connectSwitch.setChecked(false);
                        // onCheckedChanged - will call mARMinfo.disconnect()
                        // and close USB...
                    }
                }
                
            }
        }
    };

    /**
     * Asks for USB permission. Called by onClick() when the detect button is
     * clicked.
     */
    private void getUSBPermission() {
        
        UsbDevice device = null;
        HashMap<String, UsbDevice> deviceList;
        Iterator<UsbDevice> deviceIterator;

        mUsbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        deviceList = mUsbManager.getDeviceList();
        deviceIterator = deviceList.values().iterator();

        // Assume that the device is placed last in list
        while (deviceIterator.hasNext()) {
            device = deviceIterator.next();
            if (device != null && mARMinfo.isCMSISDap(device)) {
            	// Break if CMSIS-DAP device found
        		break;
            }
        }

        // Get permission to communicate with USB device. Reply in onReceive..
        if (device != null) {
            mUsbManager.requestPermission(device, mPermissionIntent);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        return false; // do not show...
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterReceiver(mUsbReceiver);
    }
}
