package com.fatman.minihttpserver.httpserver.mode;

import com.fatman.minihttpserver.httpserver.server.ServerImpl;

import java.util.logging.Logger;

/**
 * @author 肥宅快乐码
 * @date 2020/3/8 - 20:25
 */
public class HttpContextImpl {
    private String path;
    private String protocol;
    private ServerImpl server;

    public HttpContextImpl(String protocol, String path, ServerImpl server) {
        if (path == null || protocol == null || path.length() < 1 || path.charAt(0) != '/') {
            throw new IllegalArgumentException ("Illegal value for path or protocol");
        }

        this.protocol = protocol.toLowerCase();
        this.path = path;
        if (!this.protocol.equals ("http")) {
            throw new IllegalArgumentException ("Illegal value for protocol");
        }
        this.server = server;
    }

    public String getProtocol() {
        return protocol;
    }

    public String getPath() {
        return path;
    }

    public ServerImpl getServerImpl() {
        return server;
    }

    Logger getLogger () {
        return server.getLogger();
    }
}
