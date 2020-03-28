package com.fatman.minihttpserver.httpserver;

import com.fatman.minihttpserver.httpserver.mode.HttpConnection;
import com.fatman.minihttpserver.httpserver.server.ServerImpl;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * @author 肥宅快乐码
 * @date 2020/3/23 - 21:16
 */
public class Poller implements Runnable{

    private ServerImpl server;
    private Selector selector;
    private LinkedBlockingQueue<Event> events = new LinkedBlockingQueue<>();

    public Poller(ServerImpl server) {
        this.server = server;
        selector = server.getSelector();
    }

    class Event {
        private SocketChannel channel;
        private HttpConnection connection;
        private int operate;
        private boolean isIdle;

        public Event(SocketChannel channel, HttpConnection connection, int operate, boolean isIdle) {
            this.channel = channel;
            this.connection = connection;
            this.operate = operate;
            this.isIdle = isIdle;
        }
    }

    void register(SocketChannel channel, HttpConnection connection, int operate, boolean isIdle) throws InterruptedException {
        events.put(new Event(channel, connection, operate, isIdle));
    }

    @Override
    public void run() {
        int size,keyCount;
        while (!server.isFinished()) {
            try {
                size = events.size();
                Event event;
                SocketChannel channel;
                HttpConnection connection;
                for (int i = 0; i < size; i++) {
                    event = events.take();
                    // 如果正在关闭服务器，那就不用处理了，直接把新的连接continue然后take掉就可以了
                    if (server.isTerminating()) {
                        continue;
                    }
                    SelectionKey key = event.channel.register(server.getSelector(), event.operate);
                    connection = event.connection;
                    if (!event.isIdle) {
                        connection.setChannel(event.channel);
                        server.requestStarted(connection);
                        server.allConnections.add(connection);
                    }
                    // 把connection缓存到Key中
                    key.attach (connection);
                }
                keyCount = selector.select(1000);
                // 如果刚刚 select 有返回 ready keys，进行处理
                Iterator<SelectionKey> iterator =
                        keyCount > 0 ? selector.selectedKeys().iterator() : null;
                while (iterator != null && iterator.hasNext()) {
                    SelectionKey key = iterator.next();
                    if (key.isReadable()) {
                        iterator.remove();
                        channel = (SocketChannel) key.channel();
                        connection = (HttpConnection) key.attachment();
                        // 这里的这种先取消注册并设置为阻塞的读取方式与多次读取有关
                        // 因为后面是先读头部，之后再读取body等其他部分的
                        key.cancel();
                        channel.configureBlocking(true);
                        System.out.println("++++++++++" + server.idleConnections.size());
                        // 如果这个connection是之前保存着的空闲长连接，那么直接加入reqConnections开始请求（因为io流都初始化好了）
                        if (server.idleConnections.remove(connection)) {
                            System.out.println("close conn");
                            server.requestStarted(connection);
                        }
                        //do
                        server.executor.execute(new Worker(channel, connection, server));
                    }
                }
                // 调用select去掉cancel了的key
                selector.selectNow();
            } catch (InterruptedException | IOException e) {
                e.printStackTrace();
            }
        }
    }
}
