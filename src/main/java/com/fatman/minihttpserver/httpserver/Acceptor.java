package com.fatman.minihttpserver.httpserver;

import com.fatman.minihttpserver.httpserver.config.ServerConfig;
import com.fatman.minihttpserver.httpserver.mode.HttpConnection;
import com.fatman.minihttpserver.httpserver.server.ServerImpl;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ThreadFactory;

/**
 * Acceptor类，专门用于接收连接的Runnable
 *
 * @author 肥宅快乐码
 * @date 2020/3/8 - 22:58
 */
public class Acceptor implements Runnable{

    private ServerImpl server;

    public Acceptor() {
    }

    public Acceptor(ServerImpl server) {
        this.server = server;
    }

    @Override
    public void run() {
        // 如果已经完全关闭服务器，那就不用任何处理了
        while (!server.isFinished()) {
            try {
                // 如果正在关闭服务器，那就不用处理了，直接把新的连接continue然后remove掉就可以了
                if (server.isTerminating()) {
                    continue;
                }
                SocketChannel channel = server.getServerSocket().accept();
                // 根据需要开启TCPNoDelay，也就是关闭Nagle算法，减小缓存带来的延迟
                if(channel == null) {
                    continue;
                }
                if (ServerConfig.noDelay()) {
                    channel.socket().setTcpNoDelay(true);
                }
                channel.configureBlocking(false);
                // 交由
                server.getPoller().register(channel, new HttpConnection(), SelectionKey.OP_READ, false);
            } catch (InterruptedException | IOException e) {
                e.printStackTrace();
            }
        }
    }
}

