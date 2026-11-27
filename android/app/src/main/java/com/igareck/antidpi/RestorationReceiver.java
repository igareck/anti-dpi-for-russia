/* Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later */
package com.igareck.antidpi;
import android.content.*;
/** Credential-protected state is used only after the first unlock. */
public final class RestorationReceiver extends BroadcastReceiver {
 @Override public void onReceive(Context c,Intent i){
  String action=i==null?null:i.getAction();
  if(Intent.ACTION_BOOT_COMPLETED.equals(action)||Intent.ACTION_MY_PACKAGE_REPLACED.equals(action))ServiceRestoration.boot(c,Intent.ACTION_BOOT_COMPLETED.equals(action)?"boot_completed":"package_replaced");
 }
}
