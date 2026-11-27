package android.os;import java.io.*;
public class ParcelFileDescriptor implements Closeable {public int calls;public boolean fail;private final FileDescriptor fd=new FileDescriptor();public FileDescriptor getFileDescriptor(){return fd;}public void close()throws IOException{calls++;if(fail){fail=false;throw new IOException("close failed once");}}}
