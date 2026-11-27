package android.system;

import java.io.FileDescriptor;
import java.util.IdentityHashMap;

public final class Os {
    static {System.loadLibrary("session_fixture");}
    private static final IdentityHashMap<FileDescriptor,Integer> FDS=new IdentityHashMap<>();
    public static native int setsid()throws ErrnoException;
    private static native int openNull()throws ErrnoException;
    private static native void duplicate(int source,int target)throws ErrnoException;
    private static native void closeFd(int fd)throws ErrnoException;

    public static FileDescriptor open(String path,int flags,int mode)throws ErrnoException{
        if(!path.equals("/dev/null")||flags!=(OsConstants.O_RDWR|OsConstants.O_CLOEXEC)||mode!=0)
            throw new AssertionError("Unexpected bootstrap sink");
        FileDescriptor file=new FileDescriptor();
        FDS.put(file,openNull());
        return file;
    }
    public static FileDescriptor dup2(FileDescriptor source,int target)throws ErrnoException{
        if(target<0||target>2)throw new AssertionError("Unexpected target descriptor");
        duplicate(FDS.get(source),target);
        return new FileDescriptor();
    }
    public static void close(FileDescriptor file)throws ErrnoException{closeFd(FDS.remove(file));}
}
