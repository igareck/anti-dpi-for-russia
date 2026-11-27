package com.igareck.antidpi;
import android.os.IBinder;
interface ITransportService {
 int getUid() = 0;
 String startProxy(String profile, int port, boolean verbose, IBinder owner, String requestId) = 1;
 String stopProxy(String expectedInstance) = 2;
 String status() = 3;
 String drainLogs() = 4;
 String snapshotMetrics() = 5;
 String recoverProxy(String expectedInstance, String expectedEpoch) = 6;
 String startProxyUdp(String profile, int port, boolean verbose, IBinder owner, String requestId, boolean udpEnabled, String quicProfile) = 7;
 String startProxyUdpDiscord(String profile, int port, boolean verbose, IBinder owner, String requestId, boolean udpEnabled, String quicProfile, String discordProfile) = 8;
 String startYoutube(in int[] numbers, IBinder owner, String requestId) = 9;
 void destroy() = 16777114;
}
