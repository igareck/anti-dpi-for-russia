package android.net;
import java.io.*;
public final class LocalSocket implements AutoCloseable {
 public static int descriptorTransfers;
 public Credentials getPeerCredentials(){return new Credentials(2000);}
 public void setFileDescriptorsForSend(FileDescriptor[] descriptors){if(descriptors!=null)descriptorTransfers+=descriptors.length;}
 public OutputStream getOutputStream(){return new ByteArrayOutputStream();}
 public void close(){}
}
