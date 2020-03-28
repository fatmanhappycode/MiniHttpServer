package com.fatman.minihttpserver.httpserver.mode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.SocketChannel;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * @author 肥宅快乐码
 * @date 2020/3/10 - 11:40
 */
public class HttpConnection {

    HttpContextImpl context;
    SocketChannel channel;

    InputStream inputStream;

    public InputStream rawInputStream;
    public OutputStream rawOutputStream;
    String protocol;
    public long time;
    /**
     * 创建连接的时间
     */
    public volatile long creationTime;
    /**
     * 开始response的时间
     */
    public volatile long rspStartedTime;

    boolean closed = false;
    Logger logger;

    public enum State {IDLE, REQUEST, RESPONSE};
    volatile State state;

    public void setParameters(
            InputStream inputStream, OutputStream rawOutputStream, SocketChannel channel, String protocol,
            HttpContextImpl context, InputStream rawInputStream
    )
    {
        this.context = context;
        this.inputStream = inputStream;
        this.rawOutputStream = rawOutputStream;
        this.rawInputStream = rawInputStream;
        this.protocol = protocol;
        this.channel = channel;
        this.logger = context.getLogger();
    }


    public HttpContextImpl getContext() {
        return context;
    }

    public void setContext(HttpContextImpl context) {
        this.context = context;
    }

    public HttpContextImpl getHttpContext() {
        return context;
    }

    public SocketChannel getChannel() {
        return channel;
    }

    public void setChannel(SocketChannel channel) {
        this.channel = channel;
    }

    public InputStream getInputStream() {
        return inputStream;
    }

    public OutputStream getRawOutputStream() {
        return rawOutputStream;
    }

    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (logger != null && channel != null) {
            logger.finest ("Closing connection: " + channel.toString());
        }

        if (!channel.isOpen()) {
            return;
        }

        try {
            // 这里关闭输入或输出流之后，会发送tcp四次挥手的第三次
            if (rawInputStream != null) {
                rawInputStream.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        
        try {
            if (rawOutputStream != null) {
                rawOutputStream.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        try {
            channel.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {return true;}
        if (o == null || getClass() != o.getClass()) {return false;}
        HttpConnection that = (HttpConnection) o;
        return Objects.equals(context, that.context) &&
                Objects.equals(channel, that.channel);
    }

    @Override
    public int hashCode() {
        return Objects.hash(context, channel);
    }
}
