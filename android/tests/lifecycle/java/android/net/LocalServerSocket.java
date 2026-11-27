package android.net;
import java.io.FileDescriptor;
public final class LocalServerSocket implements AutoCloseable {
 public LocalServerSocket(String name){}
 public FileDescriptor getFileDescriptor(){return new FileDescriptor();}
 public LocalSocket accept(){return new LocalSocket();}
 public void close(){}
}
