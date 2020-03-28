package com.fatman.minihttpserver.httpserver.server;

import com.fatman.minihttpserver.httpserver.mode.HttpContextImpl;

import java.io.IOException;

/**
 * @author 肥宅快乐码
 * @date 2020/3/5 - 17:31
 */
public abstract class HttpServer {

    /**
     *创建一个HttpContextImpl来监听一个路径
     *
     * @param path 监听的路径
     * @return HttpContextImpl
     */
    public abstract HttpContextImpl createContext (String path);

    /**
     * 开启server，开始监听
     */
    public abstract void start () throws IOException;
}
