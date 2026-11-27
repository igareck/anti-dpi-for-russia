/* Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later */
package com.igareck.antidpi;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Manifest DUMP permission authenticates the privileged sender before delivery. */
public final class WorkerBootstrapReceiver extends BroadcastReceiver {
 @Override public void onReceive(Context context,Intent intent){
  if(intent==null)return;
  if(AdbBootstrap.ACTION.equals(intent.getAction())){
   EmbeddedAdb.bootstrapReply(intent.getStringExtra("nonce"),intent.getStringExtra("descriptor"),intent.getStringExtra("auth"));
  }else if(StopVerifier.ACTION.equals(intent.getAction())){
   PendingResult pending=goAsync();
   new Thread(()->{
    try{PacketBridge.recordVerifiedStop(context,intent.getStringExtra("instance"),intent.getStringExtra("epoch"),intent.getStringExtra("auth"));}
    finally{pending.finish();}
   },"packet-stop-verifier-receipt").start();
  }
 }
}
