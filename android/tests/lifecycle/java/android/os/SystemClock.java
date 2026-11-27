package android.os; public class SystemClock {private static final long START=System.nanoTime();public static long elapsedRealtime(){return (System.nanoTime()-START)/1000000;}}
