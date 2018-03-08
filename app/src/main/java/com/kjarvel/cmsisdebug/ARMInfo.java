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

import com.kjarvel.cmsisdebug.R;

import android.content.Intent;
import android.content.res.Resources;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.util.Log;

/**
 * High-level communication with the CMSIS-DAP probe via USB.
 * Methods in here provide status messages via text strings and return values.
 * 
 */
public class ARMInfo {

    private static final int VENDOR_KEIL  = 0xc251;
    private static final int VENDOR_MBED  = 0x0d28;
    private static final int VENDOR_ATMEL = 0x03eb;

    private Resources res;

    private Dap dap = null;
    private Usb usb = null;
    private UsbDevice device = null;
    
    public ARMInfo(Resources res) {
        this.res = res;
    }
    
    /**
     * Opens USB communication to any USB device.
     * The string 'deviceDescription' will be filled with USB device info.
     */
    public boolean open(Intent intent, UsbManager mUsbManager,
                        StringBuffer deviceDescription) {
        boolean ret = false;
        synchronized (this) {
            device = (UsbDevice) intent
            		.getParcelableExtra(UsbManager.EXTRA_DEVICE);

            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED,
                    false)) {
                if (device != null) {
                    String manufProd = res.getString(R.string.unknown_msg);
                    String devInfo;

                    usb = new Usb(device);

                    if (usb.open(mUsbManager)) {
                        ret = true;
                        devInfo = usb.getDeviceInfo();
                        if (devInfo != null) {
                            manufProd = devInfo;
                        }
                    }
                    
                    deviceDescription.append(res.getString(R.string.dev_detect) 
                            + " " + manufProd);
                            //+ " (0x"
                            //+ Integer.toHexString(vendorId) + ":0x"
                            //+ Integer.toHexString(productId) + ":" 
                            // + packetSize + ")");
                }
            } else {
                Log.d(res.getString(R.string.app_name),
                        res.getString(R.string.permission_denied) + " "
                                + device);
            }
        }
        return ret;
    }
    
    /**
     * Tries to connect to a CMSIS-DAP device.
     * The string 'cmsisDescription' will be filled with CMSIS-DAP device info.
     * 
     * @return True if successful.
     */
    public boolean connect(StringBuffer cmsisDescription) {
        int packetSize = 0;
        
        if (usb.connect()) {
            packetSize = usb.getPacketSize();

            if (packetSize > 0 && isCMSISDap(device)) 
            {
                dap = new Dap(packetSize, usb);
                getARMinfo(dap, cmsisDescription);
                return true;
            }
        }
        return false;
    }
    
    public boolean isCMSISDap(UsbDevice device)
    {
    	if (device != null) {
    		int vendorId = device.getVendorId();
    		if (vendorId == VENDOR_KEIL
    			|| vendorId == VENDOR_MBED 
                || vendorId == VENDOR_ATMEL)
    		{
    			return true;
    		}
    	}
    	return false;
    }
    
    /**
     * Disconnects a connected device.
     */
    public boolean disconnect() {
        if (dap != null) {
            dap.ledOff();
            dap.disconnect();
            dap = null;
        }

        if (usb != null) {
            usb.disconnect();
            usb.close();
            usb = null;
        }

        return true;
    }

    /**
     * Resets a connected CPU.
     */
    public boolean cpuReset() {
        if (dap != null) {
            return dap.resetPins();
        }
        return false;
    }

    /**
     * Starts a connected CPU.
     */
    public boolean cpuRun() {
        if (dap != null) {
            return dap.run();
        }
        return false;
    }

    /**
     * Halts a connected CPU.
     */
    public boolean cpuHalt() {
        if (dap != null) {
            return dap.halt();
        }
        return false;
    }
    
    /**
     * Returns a string with the current ARM Core Registers (PC, LR, SP)
     */
    public String getCoreRegs() {
        int pc = 0;
        int lr = 0;
        int sp = 0;
        
        if (dap == null) {
            return null;
        }

        pc = (int)dap.readCoreReg(15); // R15 (PC)
        lr = (int)dap.readCoreReg(14); // R14 (LR)
        sp = (int)dap.readCoreReg(13); // R13 (SP)
        
        return String.format("PC:%08x LR:%08x SP:%08x", pc, lr, sp);
    }
    
    
    /**
     * Reads a 32-bit value from a memory address
     */
    public long readAddr(long addr) {
        if (dap != null) {
            return dap.readAddr(addr);
        }
        return 0;
    }

    /**
     * Writes a 32-bit value to a memory address
     */
    public boolean writeAddr(long addr, long value) {
        if (dap != null) {
            return dap.writeAddr(addr, value);
        }
        return false;
    }

    
    /**
     * Gets the CMSIS-DAP device info (in string 't')
     */
    private boolean getARMinfo(Dap dap, StringBuffer t) {
    	String fwVerText = res.getString(R.string.fw_version);
    	String unknownText = res.getString(R.string.unknown_msg);
    	
        t.append(fwVerText + " " + dap.fwVersion() + "\n");

        dap.ledOn();
        dap.connect();

        // Read IDCODE (DPIDR)
        int reg;
        reg = (int) dap.idCode();
        t.append("IdCode: 0x" + Integer.toHexString(reg) + "\n");

        // Read COREID
        reg = (int) dap.coreId();
        t.append("CoreId: 0x" + Integer.toHexString(reg) + "\n");

        // -- Trying to read CPUID..
        reg = (int) dap.cpuId();

        int rev    = ((reg >> 20) & 0x0f);
        int patch  = (reg & 0x0f);
        int partno = ((reg >> 4) & 0x0fff);

        switch (partno) {
        case 0xC20:
            t.append("CpuId: Cortex M0 r" + rev + "p" + patch + "\n");
            break;
        case 0xC21:
            t.append("CpuId: Cortex M1 r" + rev + "p" + patch + "\n");
            break;
        case 0xC23:
            t.append("CpuId: Cortex M3 r" + rev + "p" + patch + "\n");
            break;
        case 0xC24:
            t.append("CpuId: Cortex M4 r" + rev + "p" + patch + "\n");
            break;
        case 0xC60:
            t.append("CpuId: Cortex M0+ r" + rev + "p" + patch + "\n");
            break;
        default:
            t.append("CpuId: " + unknownText + ": 0x" + 
            		 Integer.toHexString(reg) + "\n");
            break;
        }

        return true;
    }
}
