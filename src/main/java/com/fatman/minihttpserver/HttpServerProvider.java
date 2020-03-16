package com.fatman.minihttpserver;

import com.fatman.minihttpserver.httpserver.HttpServer;
import com.fatman.minihttpserver.httpserver.HttpServerImpl;

import java.io.IOException;
import java.net.InetSocketAddress;

/**
 *一个用来提供HttpServer和HttpsServer的类
 *
 * @author 肥宅快乐码
 * @date 2020/3/5 - 16:17
 */
public class HttpServerProvider {
    /**
     * 创建一个HttpServer
     *
     * @param  address
     *         Server绑定的地址
     *
     * @param  backlog
     *         可以自定义的连接排队队列，如果队列中的连接超过这个数的话就会拒绝连接
     */
    public HttpServer createHttpServer (InetSocketAddress address, int backlog) throws IOException {
        return new HttpServerImpl(address, backlog);
    }

//    public HttpsServer createMiniHttpsServer (InetSocketAddress addr, int backlog) (未实现)

}
