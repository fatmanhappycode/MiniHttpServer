package com.fatman.minihttpserver;

import com.fatman.minihttpserver.HttpServerProvider;
import com.fatman.minihttpserver.httpserver.*;

import java.io.IOException;
import java.net.InetSocketAddress;

public class RestServerSample {

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