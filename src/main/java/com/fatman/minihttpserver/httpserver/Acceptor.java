//package com.fatman.minihttpserver.httpserver;
//
//import com.fatman.minihttpserver.ServerConfig;
//
//import java.io.IOException;
//import java.nio.channels.SelectionKey;
//import java.nio.channels.Selector;
//import java.nio.channels.SocketChannel;
//import java.util.Iterator;
//import java.util.Set;
//
///**
// * Acceptor类，专门用于接收连接的Runnable
// *
// * @author 肥宅快乐码
// * @date 2020/3/8 - 22:58
// */
//public class Acceptor implements Runnable{
//
//    private ServerImpl server;
//
//    public Acceptor() {
//    }
//
//    public Acceptor(ServerImpl server) {
//        this.server = server;
//    }
//
//    @Override
//    public void run() {
//        Selector selector = server.getSelector();
//        // 如果已经完全关闭服务器，那就不用任何处理了
//        while (!server.isFinished()) {
//            try {
//                Set<SelectionKey> selected = selector.selectedKeys();
//                Iterator<SelectionKey> iter = selected.iterator();
//                while (iter.hasNext()) {
//                    SelectionKey key = iter.next();
//                    iter.remove();
//                    if (key.isAcceptable()) {
//                        // 如果正在关闭服务器，那就不用处理了，直接把新的连接continue然后remove掉就可以了
//                        if (server.isTerminating()) {
//                            continue;
//                        }
//                        SocketChannel channel = server.getServerSocket().accept();
//                        // 根据需要开启TCPNoDelay，也就是关闭Nagle算法，减小缓存带来的延迟
//                        if (ServerConfig.noDelay()) {
//                            channel.socket().setTcpNoDelay(true);
//                        }
//                        channel.configureBlocking(false);
//                        channel.register(selector, SelectionKey.OP_READ);
//                    }
//                }
//            } catch (IOException e) {
//                e.printStackTrace();
//            }
//        }
//    }
//}
