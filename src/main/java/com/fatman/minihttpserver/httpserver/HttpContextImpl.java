package com.fatman.minihttpserver.httpserver;

import com.sun.net.httpserver.HttpHandler;

import java.util.logging.Logger;

/**
 * @author 肥宅快乐码
 * @date 2020/3/8 - 20:25
 */
class HttpContextImpl {
    private String path;
    private String protocol;
    private HttpHandler handler;
    private  ServerImpl server;

    HttpContextImpl(String protocol, String path, HttpHandler handler, ServerImpl server) {
        if (path == null || protocol == null || path.length() < 1 || path.charAt(0) != '/') {
            throw new IllegalArgumentException ("Illegal value for path or protocol");
        }

        this.protocol = protocol.toLowerCase();
        this.path = path;
        if (!this.protocol.equals ("http")) {
            throw new IllegalArgumentException ("Illegal value for protocol");
        }
        this.handler = handler;
        this.server = server;
    }

    public String getProtocol() {
        return protocol;
    }

    public String getPath() {
        return path;
    }

    public HttpHandler getHandler() {
        return handler;
    }

    public ServerImpl getServerImpl() {
        return server;
    }

    Logger getLogger () {
        return server.getLogger();
    }
}
