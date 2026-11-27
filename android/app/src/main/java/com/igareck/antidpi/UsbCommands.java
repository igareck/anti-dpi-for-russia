/* Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later */
package com.igareck.antidpi;

/** Commands for a terminal opened in the extracted SDK Platform-Tools directory. */
enum UsbCommands {
    WINDOWS(".\\adb.exe", '"'),
    UNIX("./adb", '\'');

    private final String adb;
    private final char quote;

    UsbCommands(String adb,char quote){
        this.adb=adb;
        this.quote=quote;
    }

    String devices(){return adb+" devices -l";}

    String start(){
        return adb+" -d shell "+quote+"content read --uri content://"+
            AppIdentity.PACKAGE_NAME+".usb/start | sh"+quote;
    }
}
