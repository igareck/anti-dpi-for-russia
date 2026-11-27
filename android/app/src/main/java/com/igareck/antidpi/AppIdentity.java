/* Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later */
package com.igareck.antidpi;
import android.content.Context;
import android.content.pm.ApplicationInfo;
/** The installed application identity, separate from the Java/AIDL namespace. */
final class AppIdentity {
    static final String PACKAGE_NAME="com.igareck.antidpi";
    static ApplicationInfo resolve(Context context) {
        try{return context.getPackageManager().getApplicationInfo(PACKAGE_NAME,0);}
        catch(Exception e){throw new IllegalStateException("Cannot resolve owning application "+PACKAGE_NAME,e);}
    }
    private AppIdentity(){}
}
