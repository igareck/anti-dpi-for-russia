package android.system;public class ErrnoException extends Exception {public final int errno;public ErrnoException(String s,int n){super(s);errno=n;}}
