package com.fatman.minihttpserver.httpserver.server;

import com.fatman.minihttpserver.httpserver.mode.HttpContextImpl;

import java.io.IOException;
import java.net.InetSocketAddress;

/**
 * HttpServer的实现类，用于监听http
 *
 * @author 肥宅快乐码
 * @date 2020/3/5 - 16:19
 */
public class HttpServerImpl extends HttpServer {

    private ServerImpl server;

    HttpServerImpl() throws IOException {
        this(new InetSocketAddress(80), 0);
    }

    public HttpServerImpl(InetSocketAddress address, int backlog) throws IOException {
        server = new ServerImpl("http", address, backlog);
    }

    @Override
    public HttpContextImpl createContext (String path) {
        return server.createContext (path,server);
    }

    @Override
    public void start () throws IOException {
        server.start();
    }
}
