package android.net;
import java.io.*;import java.net.*;import java.util.concurrent.*;
/** Explicit host boundary: Android abstract sockets/peer credentials map to loopback sockets. */
public final class LocalServerSocket implements AutoCloseable {
 static final ConcurrentMap<String,LocalServerSocket> servers=new ConcurrentHashMap<>();
 private final String name;private final ServerSocket socket;private final FileDescriptor fd=new FileDescriptor();
 public LocalServerSocket(String name)throws IOException{this.name=name;socket=new ServerSocket(0,8,InetAddress.getLoopbackAddress());socket.setSoTimeout(250);if(servers.putIfAbsent(name,this)!=null){socket.close();throw new IOException("already bound");}}
 public FileDescriptor getFileDescriptor(){return fd;}
 public LocalSocket accept()throws IOException{return new LocalSocket(socket.accept(),true,LocalSocket.clientUid);}
 static int port(String name)throws IOException{LocalServerSocket s=servers.get(name);if(s==null)throw new IOException("no such endpoint");return s.socket.getLocalPort();}
 public void close()throws IOException{servers.remove(name,this);socket.close();}
}
