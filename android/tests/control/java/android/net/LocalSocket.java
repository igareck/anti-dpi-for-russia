package android.net;
import java.io.*;import java.net.*;
public final class LocalSocket implements AutoCloseable {
 public static volatile int clientUid=10364,serverUid=2000;
 private Socket socket;private boolean accepted;private int peer;
 public LocalSocket(){socket=new Socket();}
 LocalSocket(Socket socket,boolean accepted,int uid){this.socket=socket;this.accepted=accepted;this.peer=uid;}
 public void connect(LocalSocketAddress a)throws IOException{socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(),LocalServerSocket.port(a.name)),1000);peer=serverUid;}
 public void setSoTimeout(int value)throws IOException{socket.setSoTimeout(value);}
 public Credentials getPeerCredentials(){return new Credentials(peer);}
 public InputStream getInputStream()throws IOException{return socket.getInputStream();}
 public OutputStream getOutputStream()throws IOException{return socket.getOutputStream();}
 public void close()throws IOException{socket.close();}
}
