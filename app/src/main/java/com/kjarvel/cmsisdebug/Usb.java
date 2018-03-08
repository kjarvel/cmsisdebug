/* *************************************************************************
 *   Copyright (C) 2018 Niklas Kallman <kjarvel@gmail.com>                 *
 *   Copyright (C) 2001 Johannes Erdfelt <johannes@erdfelt.com>            *
 *   Copyright (C) 2007-2008 Daniel Drake <dsd@gentoo.org>                 *
 *   Copyright (C) 2012 Pete Batard <pete@akeo.ie>                         *
 *   Copyright (C) 2012 Nathan Hjelm <hjelmn@cs.unm.edu>                   *
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

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbRequest;

/**
 * Handles low-level USB communication.
 * 
 */
public class Usb {

    /* http://libusb.sourceforge.net/api-1.0/group__desc.html */
    private static final int STD_USB_REQUEST_GET_DESCRIPTOR = 0x06;
    private static final int LIBUSB_DT_STRING = 0x03;

    private UsbInterface intf = null;
    private UsbDeviceConnection connection = null;
    private UsbEndpoint epOut = null;
    private UsbEndpoint epIn = null;
    private UsbDevice device = null;
    UsbRequest inRequest = null;
    UsbRequest outRequest = null;


    public Usb(UsbDevice device) {
        this.device = device;
    }

    
    public boolean open(UsbManager mUsbManager) {
        connection = mUsbManager.openDevice(device);
        if (connection != null) {
            return true;
        }
        return false;
    }
    
    public void close() {
        if (connection != null) {
            connection.close();
            connection = null;
        }
    }
    
    
    /**
     *  Initialize and claim the interface, endpoints and transfer requests
     */
    public boolean connect() {
        int interfaces = device.getInterfaceCount();
        int intf_idx = 0;
        boolean claimed = false;

        if (connection != null) {

            for (intf_idx = 0; intf_idx < interfaces; intf_idx++) {
                intf = device.getInterface(intf_idx);
                if (intf != null && 
                    intf.getInterfaceClass() == UsbConstants.USB_CLASS_HID) 
                {
                    if (connection.claimInterface(intf, true)) {
                        claimed = true;
                        break;
                    }
                }
            }

            if (claimed) {
                for (int i = 0; i < intf.getEndpointCount(); i++) {
                    UsbEndpoint ep = intf.getEndpoint(i);
                    if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_INT) {
                        if (ep.getDirection() == UsbConstants.USB_DIR_OUT) {
                            epOut = ep;
                        } else {
                            epIn = ep;
                        }
                    }
                }

                if (epOut != null && epIn != null) {
                    inRequest = new UsbRequest();
                    outRequest = new UsbRequest();
                    
                    outRequest.initialize(connection, epOut);
                    inRequest.initialize(connection, epIn);
                    
                    return true;
                }
            }
        }

        return false;
    }

    /**
     *  Release and close the interface, endpoints and transfer requests
     */
    public boolean disconnect() {
        if (connection != null) {
            if (intf != null) {
                connection.releaseInterface(intf);
                intf = null;
            }
        }

        if (inRequest != null) {
        	inRequest.close();
        	inRequest = null;
        }
        
        if (outRequest != null) {
        	outRequest.close();
        	outRequest = null;
        }
        
        epOut = null;
        epIn = null;

        return true;
    }

    /**
     * Get device info strings from the raw descriptors. Code from
     * http://code.google.com/p/android/issues/detail?id=25704
     * https://issuetracker.google.com/issues/36940805
     */
    public String getDeviceInfo() {
        boolean res = false;
        StringBuilder manufProduct = new StringBuilder("");

        byte[] rawDescs = connection.getRawDescriptors();
        if (rawDescs.length > 17) {
            try {
                byte[] buffer = new byte[255];
                int idxMan = rawDescs[14];
                int idxPrd = rawDescs[15];

                int rdo = connection.controlTransfer(UsbConstants.USB_DIR_IN
                        | UsbConstants.USB_TYPE_STANDARD,
                        STD_USB_REQUEST_GET_DESCRIPTOR, (LIBUSB_DT_STRING << 8)
                        | idxMan, 0, buffer, buffer.length, 0);
                if (rdo > 2) {
                    manufProduct.append(new String(buffer, 2, rdo - 2,
                            "UTF-16LE"));
                    res = true;
                }

                rdo = connection.controlTransfer(UsbConstants.USB_DIR_IN
                        | UsbConstants.USB_TYPE_STANDARD,
                        STD_USB_REQUEST_GET_DESCRIPTOR, (LIBUSB_DT_STRING << 8)
                        | idxPrd, 0, buffer, buffer.length, 0);
                if (rdo > 2) {
                    manufProduct.append(" "
                            + new String(buffer, 2, rdo - 2, "UTF-16LE"));
                    res = true;
                }

            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            }
        }

        if (res) {
            return manufProduct.toString();
        }

        return null;
    }

    /**
     * Performs an USB 'interrupt transfer'
     */
    public boolean usbXfer(byte[] bytes, int length) {
        int i;
        ByteBuffer bBytes;
        boolean res = false;
        
        /* Clear the rest of the bytes */
        for (i = length; i < bytes.length; i++) {
            bytes[i] = (byte)0;
        }

        bBytes = ByteBuffer.wrap(bytes);        

        if (outRequest.queue(bBytes, bytes.length)) {
            if (connection.requestWait() == outRequest) {
                /* The output bytes were sent */
                if (inRequest.queue(bBytes, bytes.length)) {
                    if (connection.requestWait() == inRequest) {
                        /* The bytes array contains the input data */
                    	res = true;
                    }
                }
            }
        }

        return res;
    }

    public int getPacketSize() {
        if (epOut != null) {
            return epOut.getMaxPacketSize();
        } else {
            return 0;
        }
    }
}
