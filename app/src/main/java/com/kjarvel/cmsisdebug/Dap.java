/* *************************************************************************
 *   Copyright (C) 2018 Niklas Kallman <kjarvel@gmail.com>                 *
 *                                                                         *
 *   Copyright (C) 2016 by Maksym Hilliaka                                 *
 *   oter@frozen-team.com                                                  *
 *                                                                         *
 *   Copyright (C) 2016 by Phillip Pearson                                 *
 *   pp@myelin.co.nz                                                       *
 *                                                                         *
 *   Copyright (C) 2014 by Paul Fertser                                    *
 *   fercerpav@gmail.com                                                   *
 *                                                                         *
 *   Copyright (C) 2013 by mike brown                                      *
 *   mike@theshedworks.org.uk                                              *
 *                                                                         *
 *   Copyright (C) 2013 by Spencer Oliver                                  *
 *   spen@spen-soft.co.uk                                                  *
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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Handles CMSIS-DAP low-level communication.
 * See CMSIS-DAP documentation and OpenOCD documentation
 * https://www.keil.com/pack/doc/cmsis/DAP/html/index.html
 * http://openocd.org/doc-release/doxygen/cmsis__dap__usb_8c_source.html
 *
 */
public class Dap {
    private byte[] bytes;
    private Usb usb;
    private StringBuffer msg;

    private final int T_DP_MASK    = 0x00;
    private final int T_AP_MASK    = 0x01;
    private final int T_READ_MASK  = 0x02;
    private final int T_WRITE_MASK = 0x00;
    
    private final byte DP_IDR    = 0x00;  // IDR - IDCODE register (Read Only)
    private final byte DP_ABORT  = 0x00;  // ABORT register (Write Only)
    private final byte DP_CTRL   = 0x04;  // CTRL/STAT register
    private final byte DP_SELECT = 0x08;  // AP Select register

    private final byte AP_CSW = 0x00;     // Control/Status Word register
    private final byte AP_TAR = 0x04;     // Transfer Address register
    private final byte AP_DRW = 0x0C;     // Data Read/Write register

    private final byte CMD_DAP_Info          = 0x00;
    private final byte CMD_DAP_LED           = 0x01;
    private final byte CMD_DAP_Connect       = 0x02;
    private final byte CMD_DAP_Disconnect    = 0x03;
    private final byte CMD_DAP_TFER_Config   = 0x04;
    private final byte CMD_DAP_Transfer      = 0x05;
    private final byte CMD_DAP_TransferBlock = 0x06;
    private final byte CMD_DAP_Write_Abort   = 0x08;
    private final byte CMD_SWJ_Pins          = 0x10;
    private final byte CMD_DAP_SWJ_Clock     = 0x11;
    private final byte CMD_DAP_SWJ_Seq       = 0x12;
    private final byte CMD_DAP_SWD_Config    = 0x13;
    

    private long dpReadReg(byte addr) {
        long reg = 0;
        ByteBuffer bf;

        bytes[0] = CMD_DAP_Transfer;
        bytes[1] = 0x00; // DAP Index - ignored for SWD
        bytes[2] = 0x01; // Transfer count = 1
        bytes[3] = (byte) (T_DP_MASK | T_READ_MASK | addr); // Transfer request

        if (usb.usbXfer(bytes, 4)) {
            if (bytes[0] == CMD_DAP_Transfer && bytes[1] == 1
                    && ((int) bytes[2] & 0x01) == 0x01) {
                bf = ByteBuffer.wrap(bytes, 3, 4);
                bf.order(ByteOrder.LITTLE_ENDIAN);
                reg = bf.getInt() & 0xFFFFFFFF;
            }

        }
        return reg;
    }

    private long apBlockReadReg(byte addr) {
        long reg = 0;
        ByteBuffer bf;

        bytes[0] = CMD_DAP_TransferBlock;
        bytes[1] = 0x00; // DAP Index - ignored for SWD
        bytes[2] = 0x01; // Transfer count = 1
        bytes[3] = 0x00; // Transfer count
        bytes[4] = (byte) (T_AP_MASK | T_READ_MASK | addr); // Transfer request

        if (usb.usbXfer(bytes, 5)) {
            if (bytes[0] == CMD_DAP_TransferBlock && bytes[1] == 1 && bytes[2] == 0
                    && ((int) bytes[3] & 0x01) == 0x01) {
                bf = ByteBuffer.wrap(bytes, 4, 4);
                bf.order(ByteOrder.LITTLE_ENDIAN);
                reg = bf.getInt() & 0xFFFFFFFF;
            }

        }
        return reg;
    }

    private boolean dpWriteReg(byte addr, long reg) {
        bytes[0] = CMD_DAP_Transfer;
        bytes[1] = 0x00; // DAP Index - ignored for SWD
        bytes[2] = 0x01; // Transfer count = 1
        bytes[3] = (byte) (T_DP_MASK | T_WRITE_MASK | addr); // Transfer request
        bytes[4] = (byte) ((reg >> 0) & 0xFF);
        bytes[5] = (byte) ((reg >> 8) & 0xFF);
        bytes[6] = (byte) ((reg >> 16) & 0xFF);
        bytes[7] = (byte) ((reg >> 24) & 0xFF);

        if (usb.usbXfer(bytes, 8)) {
            if (bytes[0] == CMD_DAP_Transfer && bytes[1] == 1
                    && ((int) bytes[2] & 0x01) == 0x01) {
                return true;
            }

        }
        return false;
    }

    private boolean apWriteReg(byte addr, long reg) {
        bytes[0] = CMD_DAP_Transfer;
        bytes[1] = 0x00; // DAP Index - ignored for SWD
        bytes[2] = 0x01; // Transfer count = 1
        bytes[3] = (byte) (T_AP_MASK | T_WRITE_MASK | addr); // Transfer request
        bytes[4] = (byte) ((reg >> 0) & 0xFF);
        bytes[5] = (byte) ((reg >> 8) & 0xFF);
        bytes[6] = (byte) ((reg >> 16) & 0xFF);
        bytes[7] = (byte) ((reg >> 24) & 0xFF);

        if (usb.usbXfer(bytes, 8)) {
            if (bytes[0] == CMD_DAP_Transfer && bytes[1] == 1
                    && ((int) bytes[2] & 0x01) == 0x01) {
                return true;
            }

        }
        return false;
    }

    public String getMsgLog() {
        return msg.toString();
    }

    public Dap(int buflen, Usb usb) {
        bytes = new byte[buflen];
        msg = new StringBuffer("");
        this.usb = usb;
    }

    public String fwVersion() {
        bytes[0] = CMD_DAP_Info;
        bytes[1] = 0x04;
        if (usb.usbXfer(bytes, 2)) {
            return new String(bytes, 2, bytes[1]);
        }
        return new String("");
    }

    public long idCode() {
        // Read IDCODE (DPIDR)
        return dpReadReg(DP_IDR);
    }

    public long coreId() {
        dpWriteReg(DP_CTRL, 0x50000000);
        dpWriteReg(DP_ABORT, 0x0000001e); // Clear sticky error bits
        dpReadReg(DP_CTRL);
        dpWriteReg(DP_SELECT, 0x000000f0); // AP = 1111 ?
        return apBlockReadReg(AP_DRW);
    }

    public long cpuId() {
        dpWriteReg(DP_SELECT, 0x00000000);
        apWriteReg(AP_CSW, 0x23000002); // Configure 32-bit access
        apWriteReg(AP_TAR, 0xe000ed00); // 0xe000ed00 = CPUID address 
        return apBlockReadReg(AP_DRW);
    }
    
    public long readAddr(long addr) {
        dpWriteReg(DP_SELECT, 0x00000000);
        apWriteReg(AP_CSW, 0x23000002); // Configure 32-bit access
        apWriteReg(AP_TAR, addr);       // Place address in TAR
        return apBlockReadReg(AP_DRW);  // Read from address
    }
    
    public boolean writeAddr(long addr, long value)
    {
        dpWriteReg(DP_SELECT, 0x00000000);
        apWriteReg(AP_CSW, 0x23000002); // Configure 32-bit access
        apWriteReg(AP_TAR, addr);       // Place address in TAR
        apWriteReg(AP_DRW, value);      // Write to address
        dpReadReg(DP_CTRL);
        return true;
    }

    public long readCoreReg(int reg)
    {
        // Write to Debug Core Register Selector Register (DCRSR)
        writeAddr(0xE000EDF4, (byte)reg);
        return readAddr(0xE000EDF8); // Read DCRDR
    }
    
    public boolean halt() {
        dpWriteReg(DP_SELECT, 0x00000000);
        apWriteReg(AP_CSW, 0x23000002);
        // 0xe000edf0 = Debug Halting Control and Status Register
        apWriteReg(AP_TAR, 0xe000edf0);
        // Debug Key. 0xA05F must be written whenever this register is written.
        apWriteReg(AP_DRW, 0xa05f0003); // C_HALT | C_DEBUGEN
        dpReadReg(DP_CTRL);
        return true;
    }

    public boolean run() {
        dpWriteReg(DP_SELECT, 0x00000000);
        apWriteReg(AP_CSW, 0x23000002);

        apWriteReg(AP_TAR, 0xe000edf0);
        // Debug Key. 0xA05F must be written whenever this register is written.
        apWriteReg(AP_DRW, 0xa05f0001); // C_DEBUGEN
        dpReadReg(DP_CTRL);
        return true;
    }

    public boolean disconnect() {
        bytes[0] = CMD_DAP_Disconnect; 
        return usb.usbXfer(bytes, 1);
    }

    public boolean connect() {
        bytes[0] = CMD_DAP_Connect;
        bytes[1] = 1; // 0=JTAG, SWD=1

        if (usb.usbXfer(bytes, 2)) {
            if (bytes[0] == CMD_DAP_Connect && bytes[1] == 1) {
                msg.append("SWD connected\n");
            } else {
                msg.append("SWD not connected\n");
            }
        }

        short clock = 100; // khz
        clock *= 1000; // hz
        bytes[0] = CMD_DAP_SWJ_Clock;
        bytes[1] = (byte) (clock & 0xff);
        bytes[2] = (byte) ((clock >> 8) & 0xff);
        usb.usbXfer(bytes, 3);

        byte idle = 0;
        short wait = 64;
        short retry = 0;
        bytes[0] = CMD_DAP_TFER_Config;
        bytes[1] = idle;
        bytes[2] = (byte) (wait & 0xff);
        bytes[3] = (byte) ((wait >> 8) & 0xff);
        bytes[4] = (byte) (retry & 0xff);
        bytes[5] = (byte) ((retry >> 8) & 0xff);
        usb.usbXfer(bytes, 6);

        bytes[0] = CMD_DAP_SWD_Config; 
        bytes[1] = 0; // ?
        usb.usbXfer(bytes, 2);

        // Reset sequence 50 '1'
        bytes[0] = CMD_DAP_SWJ_Seq;
        bytes[1] = 7 * 8;
        bytes[2] = (byte) 0xFF;
        bytes[3] = (byte) 0xFF;
        bytes[4] = (byte) 0xFF;
        bytes[5] = (byte) 0xFF;
        bytes[6] = (byte) 0xFF;
        bytes[7] = (byte) 0xFF;
        bytes[8] = (byte) 0xFF;
        usb.usbXfer(bytes, 9);

        // 16bit JTAG-SWD sequence
        bytes[0] = CMD_DAP_SWJ_Seq;
        bytes[1] = 2 * 8;
        bytes[2] = (byte) 0x9E;
        bytes[3] = (byte) 0xE7;
        usb.usbXfer(bytes, 4);

        // Reset sequence 50 '1' (again)
        bytes[0] = CMD_DAP_SWJ_Seq;
        bytes[1] = 7 * 8;
        bytes[2] = (byte) 0xFF;
        bytes[3] = (byte) 0xFF;
        bytes[4] = (byte) 0xFF;
        bytes[5] = (byte) 0xFF;
        bytes[6] = (byte) 0xFF;
        bytes[7] = (byte) 0xFF;
        bytes[8] = (byte) 0xFF;
        usb.usbXfer(bytes, 9);

        // 16 cycle idle period
        bytes[0] = CMD_DAP_SWJ_Seq;
        bytes[1] = 2 * 8;
        bytes[2] = 0x00;
        bytes[3] = 0x00;
        usb.usbXfer(bytes, 4);

        // Read IDCODE (seems to be important to get halt/go working)
        idCode();

        return true;
    }

    public boolean resetPins() {
        // 16bit JTAG-SWD sequence
        bytes[0] = CMD_DAP_Write_Abort;
        bytes[1] = 2 * 8;
        bytes[2] = 0x00;
        bytes[3] = 0x1e;
        usb.usbXfer(bytes, 4);

        // Reset with pin
        bytes[0] = CMD_SWJ_Pins;
        bytes[1] = (byte) (0 << 7); // Output: nRESET
        bytes[2] = (byte) (1 << 7); // Pin select
        bytes[3] = 0;
        bytes[4] = 0;
        bytes[5] = 0;
        bytes[6] = 0;
        // Time in us (max 3s)
        // 3-6 = word
        usb.usbXfer(bytes, 7);

        // Re-connect after reset
        disconnect();
        connect();

        return true;
    }

    public boolean ledOff() {
        bytes[0] = CMD_DAP_LED;
        bytes[1] = 0; // Connect LED
        bytes[2] = 0; // LED OFF
        usb.usbXfer(bytes, 3);
        return true;
    }

    public boolean ledOn() {
        bytes[0] = CMD_DAP_LED;
        bytes[1] = 0; // Connect LED
        bytes[2] = 1; // LED ON
        usb.usbXfer(bytes, 3);
        return true;
    }
}
