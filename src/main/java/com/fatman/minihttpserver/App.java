package com.fatman.minihttpserver;

import com.fatman.minihttpserver.httpserver.server.HttpServer;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.*;

public class App {

    public static void serverStart() throws IOException {
        HttpServer httpserver = HttpServerProvider.createHttpServer(new InetSocketAddress(8080), 0);
        //监听端口8080,  

        httpserver.createContext("/RestSample");
        httpserver.start();
        System.out.println("server started");
    }

    public static void main(String[] args) throws IOException {
        serverStart();
    }
}