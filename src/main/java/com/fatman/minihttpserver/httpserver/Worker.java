package com.fatman.minihttpserver.httpserver;

import com.fatman.minihttpserver.httpserver.config.ServerConfig;
import com.fatman.minihttpserver.httpserver.iostream.IoUtil;
import com.fatman.minihttpserver.httpserver.mode.*;
import com.fatman.minihttpserver.httpserver.server.ServerImpl;
import com.sun.net.httpserver.Headers;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.logging.Level;

/**
 * @author 肥宅快乐码
 * @date 2020/3/24 - 22:43
 */
public class Worker implements Runnable{
    ServerImpl server;
    SocketChannel channel;
    HttpConnection connection;
    HttpContextImpl context;
    InputStream rawInputStream;
    OutputStream rawOutputStream;
    String protocol;
    ExchangeImpl exchange;
    boolean rejected = false;

    public Worker(SocketChannel channel, HttpConnection connection, ServerImpl server) {
        this.server = server;
        this.channel = channel;
        this.connection = connection;
        this.protocol = server.protocol;
    }

    @Override
    public void run() {
        context = connection.getHttpContext();
        boolean newConnection;
        String requestLine = null;

        try {
            // context对应着这个http请求访问的路径和处理器，而一个未解析http请求自然context为null
            if (context != null) {
                this.rawInputStream = connection.getInputStream();
                this.rawOutputStream = connection.getRawOutputStream();
                newConnection = false;
            } else {
                newConnection = true;
                rawInputStream = new BufferedInputStream(
                        new Request.ReadStream(
                                server, channel
                        ));
                rawOutputStream = new Request.WriteStream(
                        server, channel
                );

                connection.rawInputStream = rawInputStream;
                connection.rawOutputStream = rawOutputStream;
            }

            // 封装io，开始读取
            Request request = new Request(rawInputStream, rawOutputStream);
            requestLine = request.requestLine();
            if (requestLine == null) {
                server.closeConnection(connection);
                return;
            }

            // 获取请求类型（GET/POST...)
            int space = requestLine.indexOf(' ');
            if (space == -1) {
                reject(Code.HTTP_BAD_REQUEST,
                        requestLine, "Bad request line");
                return;
            }
            String method = requestLine.substring(0, space);

            // 获取请求的url
            int start = space + 1;
            space = requestLine.indexOf(' ', start);
            if (space == -1) {
                reject(Code.HTTP_BAD_REQUEST,
                        requestLine, "Bad request line");
                return;
            }
            String uriStr = requestLine.substring(start, space);
            URI uri = new URI(uriStr);

            // http请求版本(1.0/1.1...)
            start = space + 1;
            String version = requestLine.substring(start);

            Headers headers = request.headers();

            // 如果是采用Transfer-encoding，那么解析body的方式不同，而且Context-Length将被忽略，所以标记为长度-1
            String s = headers.getFirst("Transfer-encoding");
            long cLen = 0L;
            if (s != null && s.equalsIgnoreCase("chunked")) {
                cLen = -1L;
            } else {
                // 没用Transfer-encoding而用了Content-Length
                s = headers.getFirst("Content-Length");
                if (s != null) {
                    cLen = Long.parseLong(s);
                }
                if (cLen == 0) {
                    // 如果主体长度为0，那么请求已经结束，这里将connection从reqConnections中移出，并添加当前时间，加入rspConnections
                    server.requestCompleted(connection);
                }
            }

            context = server.findContext(protocol, uri.getPath());
            connection.setContext (context);

            // 找不到监听的路径
            if (context == null) {
                reject(Code.HTTP_NOT_FOUND,
                        requestLine, "No context found for request");
                return;
            }

            exchange = new ExchangeImpl(
                    method, uri, request, cLen, connection
            );

            // 看看有没有connection：close参数，1.0默认close，需要手动开启keep-alive
            String connHeader = headers.getFirst("Connection");
            Headers rspHeaders = exchange.getResponseHeaders();
            if (connHeader != null && connHeader.equalsIgnoreCase("close")) {
                exchange.close = true;
            }
            if (version.equalsIgnoreCase("http/1.0")) {
                exchange.http10 = true;
                if (connHeader == null) {
                    exchange.close = true;
                    rspHeaders.set("Connection", "close");
                } else if (connHeader.equalsIgnoreCase("keep-alive")) {
                    rspHeaders.set("Connection", "keep-alive");
                    int idle = (int) (ServerConfig.getIdleInterval() / 1000);
                    int max = ServerConfig.getMaxIdleConnections();
                    String val = "timeout=" + idle + ", max=" + max;
                    rspHeaders.set("Keep-Alive", val);
                }
            }

            if (newConnection) {
                connection.setParameters(
                        rawInputStream, rawOutputStream, channel, protocol, context, rawInputStream
                );
            }

            // 如果客户端发出expect:100-continue，意思就是客户端想要post东西（一般是比较大的），询问是否同意
            // 返回响应码100后客户端才会继续post数据
            String exp = headers.getFirst("Expect");
            if (exp != null && exp.equalsIgnoreCase("100-continue")) {
                server.logReply(100, requestLine, null);
                sendReply(
                        Code.HTTP_CONTINUE, null
                );
            }

            // 初始化一下包装的io流
            exchange.getRequestBody();
            exchange.getResponseBody();

            // +++++++++++++++++++++++++++++++++++++++++++工作++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
            String requestMethod = exchange.getRequestMethod();

            if ("GET".equalsIgnoreCase(requestMethod)) {
                Headers responseHeaders = exchange.getResponseHeaders();
                String[] strings = uriStr.split("\\.");
                String suffix = "";
                if (strings.length > 1) {
                    suffix = "." + strings[strings.length-1];
                }
                responseHeaders.set("Content-Type", ContentType.findContextType(suffix));
                byte[] bytes = IoUtil.readFileByBytes(ServerConfig.getSourcePath() + uriStr.substring(1));
                exchange.sendResponseHeaders(200, bytes.length);
                OutputStream responseBody = exchange.getResponseBody();
                responseBody.write(bytes);
                responseBody.flush();
                responseBody.close();
            }

            // 完成响应
            server.responseCompleted (connection);
            // 如果空闲的连接超过MAX_IDLE_CONNECTIONS，则不能再添加了，并且关闭连接
            if (server.idleConnections.size() >= ServerImpl.MAX_IDLE_CONNECTIONS) {
                connection.close();
                server.allConnections.remove (connection);
            } else {
                channel.configureBlocking (false);
                connection.time = server.getTime() + ServerImpl.IDLE_INTERVAL;
                server.idleConnections.add (connection);
                server.getPoller().register(channel, connection,SelectionKey.OP_READ, true);
            }
        } catch (IOException e1) {
            server.logger.log(Level.FINER, "ServerImpl.Exchange (1)", e1);
            server.closeConnection(connection);
        } catch (NumberFormatException e3) {
            reject(Code.HTTP_BAD_REQUEST,
                    requestLine, "NumberFormatException thrown");
        } catch (URISyntaxException e) {
            reject(Code.HTTP_BAD_REQUEST,
                    requestLine, "URISyntaxException thrown");
        } catch (Exception e4) {
            server.logger.log(Level.FINER, "ServerImpl.Exchange (2)", e4);
            server.closeConnection(connection);
        }
    }

    /**
     * 发生错误时执行的拒绝策略，先打印日志，再返回给客户端错误信息
     * @param code 错误码
     * @param requestStr 错误的请求头
     * @param message 错误信息
     */
    void reject (int code, String requestStr, String message) {
        rejected = true;
        server.logReply (code, requestStr, message);
        sendReply (
                code, "<h1>"+code+Code.msg(code)+"</h1>"+message
        );
        server.closeConnection(connection);
    }

    /**
     * 用于发生错误时返回结果
     * @param code 错误码
     * @param text 错误信息
     */
    void sendReply(
            int code, String text)
    {
        try {
            StringBuilder builder = new StringBuilder (512);
            builder.append ("HTTP/1.1 ")
                    .append (code).append (Code.msg(code)).append ("\r\n");

            if (text != null && text.length() != 0) {
                builder.append ("Content-Length: ")
                        .append (text.length()).append ("\r\n")
                        .append ("Content-Type: text/html\r\n");
            } else {
                builder.append ("Content-Length: 0\r\n");
                text = "";
            }
            builder.append ("Connection: close\r\n");
            builder.append ("\r\n").append (text);
            String s = builder.toString();
            byte[] b = s.getBytes("ISO8859_1");
            rawOutputStream.write (b);
            rawOutputStream.flush();
            server.closeConnection(connection);
        } catch (IOException e) {
            server.logger.log (Level.FINER, "ServerImpl.sendReply", e);
            server.closeConnection(connection);
        }
    }

}
