import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.PortUnreachableException;
import java.nio.channels.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


/*
HTTP 1.0
NON KEEP-ALIVE
 */

class HttpConnection{
    private static final int BUFFER_SIZE = 1024;//1 kb
    private SocketChannel socketChannel;
    private ArrayList<ByteBuffer> buffers = new ArrayList<>();
    private String request;
    private boolean handled = false;

    public HttpConnection(){

    }

    public HttpConnection(SocketChannel socketChannel){
        this.socketChannel = socketChannel;
    }

    public void setSocketChannel(SocketChannel socketChannel) {
        this.socketChannel = socketChannel;
    }

    public ArrayList<ByteBuffer> getBuffers() {
        return buffers;
    }

    public void setBuffers(ArrayList<ByteBuffer> buffers) {
        this.buffers = buffers;
    }

    public String getRequest() {
        return request;
    }

    public void setRequest(String request) {
        this.request = request;
    }

    public void convertByteRequestToString(){
        StringBuilder stringBuilder = new StringBuilder();
        for(ByteBuffer buffer : buffers){
            String curr = new String(buffer.array());
            stringBuilder.append(curr);
        }
        request = stringBuilder.toString();
    }

    public SocketChannel getSocketChannel() {
        return socketChannel;
    }

    public boolean isHandled() {
        return handled;
    }

    public void setHandled(boolean handled) {
        this.handled = handled;
    }

    public void addBuffer(ByteBuffer buffer){
        buffers.add(buffer);
    }

    public ByteBuffer getLastBuffer(){
        ByteBuffer lastBuffer;
        if(buffers.size() ==  0) {
            lastBuffer = ByteBuffer.allocate(BUFFER_SIZE);//allocate 16kb buffer
            buffers.add(lastBuffer);
        }
        else
            lastBuffer = buffers.get(buffers.size() == 0 ? 0 : buffers.size() - 1);
        return lastBuffer;
    }

    public int getBufferSize(){
        return BUFFER_SIZE;
    }
}

class Server{
    private ExecutorService es = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    private Map<SocketChannel, HttpConnection> connectionsStore = new HashMap<>();
    private final int port;
    private ServerSocketChannel serverSocketChannel;
    private Selector selector;

    public Server(int port){
        this.port = port;
    }

    public void closeConnection(HttpConnection connection) throws IOException {
        connection.setHandled(true);
        SocketChannel socketChannel = connection.getSocketChannel();
        socketChannel.close();
        connectionsStore.remove(connection);
    }

    public void serve(HttpConnection connection){
        if(connection.isHandled())
            return;

        es.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    connection.convertByteRequestToString();
                    System.out.println(connection.getRequest());//DEBUG

                    String answer = "Hello, World";
                    SocketChannel socketChannel = connection.getSocketChannel();
                    socketChannel.write(ByteBuffer.wrap(new String("HTTP/1.0 200 OK\n" +
                                                                    "Content-Length: " + answer.length() + " \n" +
                                                                    "Content-Type: text/html\n" +
                                                                    "Connection: Closed\n\n" +
                                                                    answer).getBytes()));
                    closeConnection(connection);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });
    }

    public void handleRead(SocketChannel socketChannel) throws IOException {
        HttpConnection connection = connectionsStore.get(socketChannel);
        ByteBuffer lastBuffer = connection.getLastBuffer();
        int BUFFER_SIZE = connection.getBufferSize();
        while(true){
            int count = socketChannel.read(lastBuffer);
            if(count > 0){
                if(!lastBuffer.hasRemaining()){
                    lastBuffer = ByteBuffer.allocate(BUFFER_SIZE);
                    connection.addBuffer(lastBuffer);
                }
            }
            else if(count == 0) {
                serve(connection);
                break;
            }
            else{
                //разрыв соединения по желанию клиента
                closeConnection(connection);
                break;
            }
        }
    }

    public void handleConnect() throws IOException {
        SocketChannel socketChannel = (SocketChannel) serverSocketChannel.accept();
        socketChannel.configureBlocking(false);
        socketChannel.register(selector, SelectionKey.OP_READ);
        HttpConnection connection = new HttpConnection(socketChannel);
        connectionsStore.put(socketChannel, connection);
    }

    public void handle() throws IOException {
        serverSocketChannel = ServerSocketChannel.open();
        serverSocketChannel.bind(new InetSocketAddress(port));

        selector = Selector.open();
        serverSocketChannel.configureBlocking(false);
        serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);

        while(true){
            int selectionKeysCount = selector.select();
            if(selectionKeysCount == 0)
                continue;

            Set<SelectionKey> selectedKeys = selector.selectedKeys();
            Iterator<SelectionKey> it = selectedKeys.iterator();
            while(it.hasNext()){
                SelectionKey currSelectionKey = it.next();
                if(currSelectionKey.channel() == serverSocketChannel){
                    try {
                        handleConnect();
                    }
                    catch(IOException ex){
                        ex.printStackTrace();
                    }
                }
                else if(currSelectionKey.isReadable()){
                    try {
                        handleRead((SocketChannel) currSelectionKey.channel());
                    }
                    catch(IOException ex){
                        ex.printStackTrace();
                    }
                }
            }
            selectedKeys.clear();
        }
    }
}

public class Main {
    public static void main(String[] args) throws IOException {
        int port;
        if(args.length > 0){
            try {
                port = Integer.parseInt(args[0]);
                if(port < 0 || port > 65535)
                    throw new IllegalArgumentException("Invalid port: " + String.valueOf(port));
                Server server = new Server(port);
                server.handle();
            }
            catch (IllegalArgumentException ex){
                System.err.println("Не правильно задан порт");
            }
        }
        else{
            System.err.println("Не задан номер порта");
            System.exit(-1);
        }
    }
}

