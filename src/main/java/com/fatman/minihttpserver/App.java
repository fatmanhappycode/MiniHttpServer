package com.fatman.minihttpserver;

import com.fatman.minihttpserver.HttpServerProvider;
import com.fatman.minihttpserver.httpserver.*;

import java.io.IOException;
import java.net.InetSocketAddress;

/**
 * @author 肥宅快乐码
 */
public class App {

    private static void serverStart() throws IOException {
        //监听端口8080
        HttpServer httpserver = HttpServerProvider.createHttpServer(new InetSocketAddress(8080), 0);

        httpserver.createContext("/RestSample");
        httpserver.start();
        System.out.println("server started");
    }

    public static void main(String[] args) throws IOException {
        serverStart();
    }
}