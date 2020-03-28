package com.fatman.minihttpserver.httpserver.server;

import com.fatman.minihttpserver.httpserver.Acceptor;
import com.fatman.minihttpserver.httpserver.Poller;
import com.fatman.minihttpserver.httpserver.config.ServerConfig;
import com.fatman.minihttpserver.httpserver.iostream.IoUtil;
import com.fatman.minihttpserver.httpserver.iostream.LeftOverInputStream;
import com.fatman.minihttpserver.httpserver.iostream.WriteFinishedEvent;
import com.fatman.minihttpserver.httpserver.mode.*;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.sun.net.httpserver.Headers;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author 肥宅快乐码
 * @date 2020/3/5 - 17:37
 */
public class ServerImpl {

    /**
     * http或https
     */
    public String protocol;

    /**
     * 保存HttpContextImpl的List
     */
    private List<HttpContextImpl> contexts;

    /**
     * 负责接收连接用的类
     */
    private Acceptor acceptor;

    public ExecutorService executor;

    private InetSocketAddress address;
    private ServerSocketChannel serverSocket;
    private Selector selector;

    private volatile boolean finished = false;
    private volatile boolean terminating = false;
    private boolean bound = false;
    private boolean started = false;

    /**
     * 长连接时，连接如果没有任务，就加进去
     * 如果超过一定时间没有任务，则主动断开长连接
     */
    public Set<HttpConnection> idleConnections;
    /**
     * 方便在stop等情况下直接断开所有连接
     */
    public Set<HttpConnection> allConnections;

    /**
     * 防止请求或响应超时,超时时由定时线程断开连接
     */
    private Set<HttpConnection> reqConnections;
    private Set<HttpConnection> rspConnections;

    private Poller poller;

    private List<Event> events;
    private final Object loLock = new Object();
    private volatile long time;

    private final static int CLOCK_TICK = ServerConfig.getClockTick();
    public final static long IDLE_INTERVAL = ServerConfig.getIdleInterval();
    public final static int MAX_IDLE_CONNECTIONS = ServerConfig.getMaxIdleConnections();
    private final static long TIMER_MILLIS = ServerConfig.getTimerMillis ();
    private final static long MAX_REQ_TIME = getTimeMillis(ServerConfig.getMaxReqTime());
    private final static long MAX_RSP_TIME=getTimeMillis(ServerConfig.getMaxRspTime());
    private final static boolean REQ_RSP_CLEAN_ENABLED = MAX_REQ_TIME != -1 || MAX_RSP_TIME != -1;

    private Timer ServerTimer, CleanReqRspTask;
    public Logger logger;

    ServerImpl(String protocol, InetSocketAddress address, int backlog) throws IOException {
        this.protocol = protocol;
        this.address = address;
        contexts = new LinkedList<>();
        this.logger = Logger.getLogger ("com.fatman.minihttpserver.httpserver");

        serverSocket = ServerSocketChannel.open();
        if (address != null) {
            serverSocket.bind(address, backlog);
            bound = true;
        }
        selector = Selector.open();
        serverSocket.configureBlocking(false);
        serverSocket.register(selector, SelectionKey.OP_ACCEPT);

        acceptor = new Acceptor(this);

        allConnections = Sets.newConcurrentHashSet();
        idleConnections = Sets.newConcurrentHashSet();
        reqConnections = Sets.newConcurrentHashSet();
        rspConnections = Sets.newConcurrentHashSet();

        time = System.currentTimeMillis();
        ServerTimer = new Timer ("server-ServerTimer", true);
        ServerTimer.schedule (new ServerTimerTask(), CLOCK_TICK, CLOCK_TICK);
        if (REQ_RSP_CLEAN_ENABLED) {
            CleanReqRspTask = new Timer ("server-CleanReqRspTask", true);
            CleanReqRspTask.schedule (new CleanResRspConnTask(),TIMER_MILLIS,TIMER_MILLIS);
            logger.config ("HttpServer CleanReqRspTask enabled period in ms:  "+TIMER_MILLIS);
            logger.config ("MAX_REQ_TIME:  "+MAX_REQ_TIME);
            logger.config ("MAX_RSP_TIME:  "+MAX_RSP_TIME);
        }
        events = new LinkedList<Event>();

        logger.config ("HttpServer created "+protocol+" "+ address);
    }

    synchronized HttpContextImpl createContext(String path, ServerImpl server) {
        if (path == null) {
            throw new NullPointerException ("路径为null");
        }

        HttpContextImpl context = new HttpContextImpl (protocol, path, server);
        contexts.add (context);
        logger.config ("context created: " + path);
        return context;
    }

    void start() throws IOException {
        if (!bound || started || finished) {
            throw new IllegalStateException ("server未绑定端口或处于已启动或已关闭状态");
        }

        poller = new Poller(ServerImpl.this);
        Thread pollerThread = new Thread(poller, address.getPort() + "Poller");
        pollerThread.start();

        Thread t = new Thread (acceptor,address.getPort() + "Acceptor");

        if (executor == null) {
            ThreadFactory namedThreadFactory = new ThreadFactoryBuilder()
                    .setNameFormat(address.getPort()+"worker-%d").build();
            executor = new ThreadPoolExecutor(10,200,60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(1024), namedThreadFactory, new ThreadPoolExecutor.AbortPolicy());
            ((ThreadPoolExecutor) executor).prestartAllCoreThreads();
        }

        started = true;
        t.start();
    }

    public Poller getPoller() {
        return poller;
    }

//    private static class DefaultExecutor implements Executor {
//        @Override
//        public void execute (Runnable task) {
//            task.run();
//        }
//    }

    /**
     * 推迟delay秒后完全关闭，在这个过程中terminating被设置为true，不再接收新请求了
     * @param delay 推迟的秒数
     */
    public void stop (int delay) {
        if (delay < 0) {
            throw new IllegalArgumentException ("negative delay parameter");
        }
        terminating = true;
        try { serverSocket.close(); } catch (IOException ignored) {}
        selector.wakeup();
        long latest = System.currentTimeMillis() + delay * 1000;
        while (System.currentTimeMillis() < latest) {
            delay();
            if (finished) {
                break;
            }
        }
        finished = true;
        selector.wakeup();
        synchronized (allConnections) {
            for (HttpConnection c : allConnections) {
                c.close();
            }
        }
        allConnections.clear();
        idleConnections.clear();
        ServerTimer.cancel();
        if (REQ_RSP_CLEAN_ENABLED) {
            CleanReqRspTask.cancel();
        }
    }

    private void delay() {
        Thread.yield();
        try { Thread.sleep (200); } catch (InterruptedException ignored) {}
    }

    public boolean isFinished() {
        return finished;
    }

    public boolean isTerminating() {
        return terminating;
    }

    public Selector getSelector() {
        return selector;
    }

    public ServerSocketChannel getServerSocket() {
        return serverSocket;
    }

    /**
     * 获取系统时间
     * @return time
     */
    public long getTime() {
        return time;
    }

    /**
     * 关闭连接，清出队列
     * @param connection 连接
     */
    public void closeConnection(HttpConnection connection) {
        connection.close();
        allConnections.remove(connection);
        switch (connection.getState()) {
            case REQUEST:
                reqConnections.remove(connection);
                break;
            case RESPONSE:
                rspConnections.remove(connection);
                break;
            case IDLE:
                idleConnections.remove(connection);
                break;
            default:;
        }
    }

//    class Acceptor implements Runnable{
//
//        private ServerImpl server;
//
//        public Acceptor() {
//        }
//
//        public Acceptor(ServerImpl server) {
//            this.server = server;
//        }
//
//        final LinkedList<HttpConnection> connectionsToRegister =
//                new LinkedList<HttpConnection>();
//
//        /**
//         * 处理event，将连接加入idleConnections和等待重新注册的connectionsToRegister列表中等
//         * @param event 包含ExchangeImpl
//         */
//        private void handleEvent (Event event) {
//            ExchangeImpl t = event.exchange;
//            HttpConnection c = t.getConnection();
//            try {
//                if (event instanceof WriteFinishedEvent) {
//                    if (terminating) {
//                        finished = true;
//                    }
//                    // 完成响应
//                    responseCompleted (c);
//                    LeftOverInputStream is = t.getOriginalInputStream();
//                    if (!is.isEOF()) {
//                        t.close = true;
//                    }
//                    // 如果空闲的连接超过MAX_IDLE_CONNECTIONS，则不能再添加了，并且关闭连接
//                    if (t.close || idleConnections.size() >= MAX_IDLE_CONNECTIONS) {
//                        c.close();
//                        allConnections.remove (c);
//                    } else {
//                        if (is.isDataBuffered()) {
//                            /* don't re-enable the interestops, just handle it */
//                            requestStarted (c);
//                            handle (c.getChannel(), c);
//                        } else {
//                            connectionsToRegister.add (c);
//                        }
//                    }
//                }
//            } catch (IOException e) {
//                logger.log (
//                        Level.FINER, "Dispatcher (1)", e
//                );
//                c.close();
//            }
//        }
//
//        /**
//         * 把刚刚的key重新用非阻塞的方式监听起来
//         * 并且把连接加入idleConnections空闲连接中
//         * @param connection 连接
//         */
//        void reRegister (HttpConnection connection) {
//            try {
//                SocketChannel chan = connection.getChannel();
//                chan.configureBlocking (false);
//                SelectionKey key = chan.register (selector, SelectionKey.OP_READ);
//                key.attach (connection);
//                connection.time = getTime() + IDLE_INTERVAL;
//                idleConnections.add (connection);
//            } catch (IOException e) {
//                logger.log(Level.FINER, "Dispatcher(8)", e);
//                connection.close();
//            }
//        }
//
//        @Override
//        public void run() {
//            Selector selector = server.getSelector();
//            // 如果已经完全关闭服务器，那就不用任何处理了
//            while (!server.isFinished()) {
//                try {
//                    List<Event> list = null;
//                    synchronized (loLock) {
//                        if (events.size() > 0) {
//                            list = events;
//                            events = new LinkedList<Event>();
//                        }
//                    }
//
//                    if (list != null) {
//                        for (Event r: list) {
//                            handleEvent (r);
//                        }
//                    }
//
//                    for (HttpConnection c : connectionsToRegister) {
//                        reRegister(c);
//                    }
//                    connectionsToRegister.clear();
//
//                    selector.select(1000);
//
//                    Set<SelectionKey> selected = selector.selectedKeys();
//                    Iterator<SelectionKey> iterator = selected.iterator();
//                    while (iterator.hasNext()) {
//                        SelectionKey key = iterator.next();
//                        iterator.remove();
//                        if (key.isAcceptable()) {
//                            // 如果正在关闭服务器，那就不用处理了，直接把新的连接continue然后remove掉就可以了
//                            if (server.isTerminating()) {
//                                continue;
//                            }
//                            SocketChannel channel = server.getServerSocket().accept();
//                            System.out.println("accept"+channel.getRemoteAddress());
//                            // 根据需要开启TCPNoDelay，也就是关闭Nagle算法，减小缓存带来的延迟
//                            if (ServerConfig.noDelay()) {
//                                channel.socket().setTcpNoDelay(true);
//                            }
//                            channel.configureBlocking(false);
//                            SelectionKey newKey = channel.register(selector, SelectionKey.OP_READ);
//                            HttpConnection connection = new HttpConnection ();
//                            connection.setChannel(channel);
//                            // 把connection缓存到Key中
//                            newKey.attach (connection);
//                            requestStarted (connection);
//                            allConnections.add (connection);
//                        } else {
//                            try {
//                                if (key.isReadable()) {
//                                    SocketChannel channel = (SocketChannel) key.channel();
//                                    HttpConnection connection = (HttpConnection) key.attachment();
//
//                                    // 这里的这种先取消注册并设置为阻塞的读取方式与多次读取有关
//                                    // 因为后面是先读头部，之后再读取body等其他部分的
//                                    key.cancel();
//                                    channel.configureBlocking (true);
//                                    System.out.println("++++++++++"+idleConnections.size());
//                                    // 如果这个connection是之前保存着的空闲长连接，那么直接加入reqConnections开始请求（因为io流都初始化好了）
//                                    if (idleConnections.remove(connection)) {
//                                        System.out.println("close conn");
//                                        requestStarted(connection);
//                                    }
//
//                                    // 调用handle进行后续处理
//                                    handle(channel, connection);
//                                } else {
//                                    assert false;
//                                }
//                            } catch (CancelledKeyException e) {
//                                handleException(key, null);
//                            } catch (IOException e) {
//                                handleException(key, e);
//                            }
//                        }
//                    }
//                    // 调用select去掉cancel了的key
//                    selector.selectNow();
//                } catch (IOException e) {
//                    logger.log (Level.FINER, "Dispatcher (4)", e);
//                } catch (Exception e) {
//                    logger.log (Level.FINER, "Dispatcher (7)", e);
//                }
//            }
//        }
//
//        void handle (SocketChannel channel, HttpConnection connection) {
//                Exchange t = new Exchange (channel, protocol, connection);
//                executor.execute (t);
//        }
//
//        private void handleException (SelectionKey key, Exception e) {
//            HttpConnection conn = (HttpConnection)key.attachment();
//            if (e != null) {
//                logger.log (Level.FINER, "Dispatcher (2)", e);
//            }
//            closeConnection(conn);
//        }
//    }

//    class Exchange implements Runnable {
//        SocketChannel channel;
//        HttpConnection connection;
//        HttpContextImpl context;
//        InputStream rawInputStream;
//        OutputStream rawOutputStream;
//        String protocol;
//        ExchangeImpl exchange;
//        boolean rejected = false;
//
//        Exchange(SocketChannel channel, String protocol, HttpConnection connection) {
//            this.channel = channel;
//            this.connection = connection;
//            this.protocol = protocol;
//        }
//
//        @Override
//        public void run() {
//            context = connection.getHttpContext();
//            boolean newConnection;
//            String requestLine = null;
//
//            try {
//                // context对应着这个http请求访问的路径和处理器，而一个未解析http请求自然context为null
//                if (context != null) {
//                    this.rawInputStream = connection.getInputStream();
//                    this.rawOutputStream = connection.getRawOutputStream();
//                    newConnection = false;
//                } else {
//                    newConnection = true;
//                    rawInputStream = new BufferedInputStream(
//                            new Request.ReadStream(
//                                    ServerImpl.this, channel
//                            ));
//                    rawOutputStream = new Request.WriteStream(
//                            ServerImpl.this, channel
//                    );
//
//                    connection.rawInputStream = rawInputStream;
//                    connection.rawOutputStream = rawOutputStream;
//                }
//
//                // 封装io，开始读取
//                Request request = new Request(rawInputStream, rawOutputStream);
//                requestLine = request.requestLine();
//                if (requestLine == null) {
//                    closeConnection(connection);
//                    return;
//                }
//
//                // 获取请求类型（GET/POST...)
//                int space = requestLine.indexOf(' ');
//                if (space == -1) {
//                    reject(Code.HTTP_BAD_REQUEST,
//                            requestLine, "Bad request line");
//                    return;
//                }
//                String method = requestLine.substring(0, space);
//
//                // 获取请求的url
//                int start = space + 1;
//                space = requestLine.indexOf(' ', start);
//                if (space == -1) {
//                    reject(Code.HTTP_BAD_REQUEST,
//                            requestLine, "Bad request line");
//                    return;
//                }
//                String uriStr = requestLine.substring(start, space);
//                URI uri = new URI(uriStr);
//
//                // http请求版本(1.0/1.1...)
//                start = space + 1;
//                String version = requestLine.substring(start);
//
//                Headers headers = request.headers();
//
//                // 如果是采用Transfer-encoding，那么解析body的方式不同，而且Context-Length将被忽略，所以标记为长度-1
//                String s = headers.getFirst("Transfer-encoding");
//                long cLen = 0L;
//                if (s != null && s.equalsIgnoreCase("chunked")) {
//                    cLen = -1L;
//                } else {
//                    // 没用Transfer-encoding而用了Content-Length
//                    s = headers.getFirst("Content-Length");
//                    if (s != null) {
//                        cLen = Long.parseLong(s);
//                    }
//                    if (cLen == 0) {
//                        // 如果主体长度为0，那么请求已经结束，这里将connection从reqConnections中移出，并添加当前时间，加入rspConnections
//                        requestCompleted(connection);
//                    }
//                }
//
//                context = findContext(protocol, uri.getPath());
//                connection.setContext (context);
//
//                // 找不到监听的路径
//                if (context == null) {
//                    reject(Code.HTTP_NOT_FOUND,
//                            requestLine, "No context found for request");
//                    return;
//                }
//
//                exchange = new ExchangeImpl(
//                        method, uri, request, cLen, connection
//                );
//
//                // 看看有没有connection：close参数，1.0默认close，需要手动开启keep-alive
//                String connHeader = headers.getFirst("Connection");
//                Headers rspHeaders = exchange.getResponseHeaders();
//                if (connHeader != null && connHeader.equalsIgnoreCase("close")) {
//                    exchange.close = true;
//                }
//                if (version.equalsIgnoreCase("http/1.0")) {
//                    exchange.http10 = true;
//                    if (connHeader == null) {
//                        exchange.close = true;
//                        rspHeaders.set("Connection", "close");
//                    } else if (connHeader.equalsIgnoreCase("keep-alive")) {
//                        rspHeaders.set("Connection", "keep-alive");
//                        int idle = (int) (ServerConfig.getIdleInterval() / 1000);
//                        int max = ServerConfig.getMaxIdleConnections();
//                        String val = "timeout=" + idle + ", max=" + max;
//                        rspHeaders.set("Keep-Alive", val);
//                    }
//                }
//
//                if (newConnection) {
//                    connection.setParameters(
//                            rawInputStream, rawOutputStream, channel, protocol, context, rawInputStream
//                    );
//                }
//
//                // 如果客户端发出expect:100-continue，意思就是客户端想要post东西（一般是比较大的），询问是否同意
//                // 返回响应码100后客户端才会继续post数据
//                String exp = headers.getFirst("Expect");
//                if (exp != null && exp.equalsIgnoreCase("100-continue")) {
//                    logReply(100, requestLine, null);
//                    sendReply(
//                            Code.HTTP_CONTINUE, null
//                    );
//                }
//
//                // 初始化一下包装的io流
//                exchange.getRequestBody();
//                exchange.getResponseBody();
//
//                // +++++++++++++++++++++++++++++++++++++++++++工作++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
//                String requestMethod = exchange.getRequestMethod();
//
//                if ("GET".equalsIgnoreCase(requestMethod)) {
//                    Headers responseHeaders = exchange.getResponseHeaders();
//                    String[] strings = uriStr.split("\\.");
//                    String suffix = "";
//                    if (strings.length > 1) {
//                        suffix = "." + strings[strings.length-1];
//                    }
//                    responseHeaders.set("Content-Type", ContentType.findContextType(suffix));
//                    byte[] bytes = IoUtil.readFileByBytes(ServerConfig.getSourcePath() + uriStr.substring(1));
//                    exchange.sendResponseHeaders(200, bytes.length);
//                    OutputStream responseBody = exchange.getResponseBody();
//                    responseBody.write(bytes);
//                    responseBody.flush();
//                    responseBody.close();
//                }
//
//            } catch (IOException e1) {
//                logger.log(Level.FINER, "ServerImpl.Exchange (1)", e1);
//                closeConnection(connection);
//            } catch (NumberFormatException e3) {
//                reject(Code.HTTP_BAD_REQUEST,
//                        requestLine, "NumberFormatException thrown");
//            } catch (URISyntaxException e) {
//                reject(Code.HTTP_BAD_REQUEST,
//                        requestLine, "URISyntaxException thrown");
//            } catch (Exception e4) {
//                logger.log(Level.FINER, "ServerImpl.Exchange (2)", e4);
//                closeConnection(connection);
//            }
//        }
//
//        /**
//         * 发生错误时执行的拒绝策略，先打印日志，再返回给客户端错误信息
//         * @param code 错误码
//         * @param requestStr 错误的请求头
//         * @param message 错误信息
//         */
//        void reject (int code, String requestStr, String message) {
//            rejected = true;
//            logReply (code, requestStr, message);
//            sendReply (
//                    code, "<h1>"+code+Code.msg(code)+"</h1>"+message
//            );
//            closeConnection(connection);
//        }
//
//        /**
//         * 用于发生错误时返回结果
//         * @param code 错误码
//         * @param text 错误信息
//         */
//        void sendReply(
//                int code, String text)
//        {
//            try {
//                StringBuilder builder = new StringBuilder (512);
//                builder.append ("HTTP/1.1 ")
//                        .append (code).append (Code.msg(code)).append ("\r\n");
//
//                if (text != null && text.length() != 0) {
//                    builder.append ("Content-Length: ")
//                            .append (text.length()).append ("\r\n")
//                            .append ("Content-Type: text/html\r\n");
//                } else {
//                    builder.append ("Content-Length: 0\r\n");
//                    text = "";
//                }
//                builder.append ("Connection: close\r\n");
//                builder.append ("\r\n").append (text);
//                String s = builder.toString();
//                byte[] b = s.getBytes("ISO8859_1");
//                rawOutputStream.write (b);
//                rawOutputStream.flush();
//                closeConnection(connection);
//            } catch (IOException e) {
//                logger.log (Level.FINER, "ServerImpl.sendReply", e);
//                closeConnection(connection);
//            }
//        }
//
//    }

    /**
     * 错误返回时打印日志
     * @param code 错误码
     * @param requestStr 请求行
     * @param text 错误信息（Bad Request之类的）
     */
    public void logReply(int code, String requestStr, String text) {
        if (!logger.isLoggable(Level.FINE)) {
            return;
        }
        if (text == null) {
            text = "";
        }
        String r;
        if (requestStr.length() > 80) {
            r = requestStr.substring (0, 80) + "<TRUNCATED>";
        } else {
            r = requestStr;
        }
        String message = r + " [" + code + " " +
                Code.msg(code) + "] ("+text+")";
        logger.fine (message);
    }

    /**
     * 请求开始
     * @param connection 连接
     */
    public void requestStarted(HttpConnection connection) {
        connection.creationTime = getTime();
        connection.setState(HttpConnection.State.REQUEST);
        reqConnections.add(connection);
    }

    /**
     * 请求完成，将请求移出reqConnections，放入rspConnections
     * @param connection 连接
     */
    public void requestCompleted(HttpConnection connection) {
        reqConnections.remove (connection);
        // 设置开始响应的时间
        connection.rspStartedTime = getTime();
        rspConnections.add (connection);
        connection.setState (HttpConnection.State.RESPONSE);
    }

    /**
     * 响应完成
     * @param connection 连接
     */
    public void responseCompleted(HttpConnection connection) {
        assert connection.getState() == HttpConnection.State.RESPONSE;
        rspConnections.remove (connection);
        connection.setState (HttpConnection.State.IDLE);
    }

    /**
     * 查找context
     * @param protocol 协议
     * @param path 路径
     * @return HttpContextImpl
     */
    public synchronized  HttpContextImpl findContext(String protocol, String path) {
        protocol = protocol.toLowerCase();
        String longest = "";
        HttpContextImpl context = null;
        for ( HttpContextImpl ctx: contexts) {
            if (!ctx.getProtocol().equals(protocol)) {
                continue;
            }
            String cPath = ctx.getPath();
            // 只要访问的路径的开头包含监听的路径，就算匹配。
            // 例如监听/abc,如果访问/abc/b.jpg，虽然路径不一样，但是却是匹配的
            if (!path.startsWith(cPath)) {
                continue;
            }
            if (cPath.length() > longest.length()) {
                longest = cPath;
                context = ctx;
            }
        }
        return context;
    }

    public void addEvent (Event r) {
        synchronized (loLock) {
            events.add (r);
            selector.wakeup();
        }
    }

    /**
     * 获取日志
     * @return Logger
     */
    public Logger getLogger () {
        return logger;
    }

    /**
     * 用于定时清理长连接（空闲的长连接）
     */
    class ServerTimerTask extends TimerTask {
        @Override
        public void run () {
            LinkedList<HttpConnection> toClose = new LinkedList<>();
            // 更新系统时间
            time = System.currentTimeMillis();
            for (HttpConnection c : idleConnections) {
                if (c.time <= time) {
                    toClose.add (c);
                }
            }
            for (HttpConnection c : toClose) {
                System.out.println("close2");
                idleConnections.remove (c);
                allConnections.remove (c);
                c.close();
            }
        }
    }

    /**
     * 用于定时清理超时的请求或超时的响应
     */
    class CleanResRspConnTask extends TimerTask {

        @Override
        public void run () {
            LinkedList<HttpConnection> toClose = new LinkedList<>();
            time = System.currentTimeMillis();
            if (MAX_REQ_TIME != -1) {
                for (HttpConnection c : reqConnections) {
                    if (c.creationTime + TIMER_MILLIS + MAX_REQ_TIME <= time) {
                        toClose.add (c);
                    }
                }
                for (HttpConnection c : toClose) {
                    logger.log (Level.FINE, "closing: no request: " + c);
                    reqConnections.remove (c);
                    allConnections.remove (c);
                    c.close();
                }
            }
            toClose = new LinkedList<>();
            if (MAX_RSP_TIME != -1) {
                for (HttpConnection c : rspConnections) {
                    if (c.rspStartedTime + TIMER_MILLIS +MAX_RSP_TIME <= time) {
                        toClose.add (c);
                    }
                }
                for (HttpConnection c : toClose) {
                    logger.log (Level.FINE, "closing: no response: " + c);
                    rspConnections.remove (c);
                    allConnections.remove (c);
                    c.close();
                }
            }
        }
    }

    /**
     * 把秒转换为毫秒的小方法
     * @param secs 秒
     * @return long 毫秒
     */
    private static long getTimeMillis(long secs) {
        if (secs == -1) {
            return -1;
        } else {
            return secs * 1000;
        }
    }
}
