package android.os;public final class Process {public static int myPid(){return (int)ProcessHandle.current().pid();}}
