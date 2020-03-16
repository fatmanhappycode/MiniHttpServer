package com.fatman.minihttpserver;

/**
 * @author 肥宅快乐码
 * @date 2020/3/10 - 12:07
 */
public class ServerConfig {
    private static final int DEFAULT_CLOCK_TICK = 10000 ; // 10 sec.

    /* These values must be a reasonable multiple of clockTick */
    private static final long DEFAULT_IDLE_INTERVAL = 30 ; // 5 min
    private static final int DEFAULT_MAX_IDLE_CONNECTIONS = 200 ;

    private static final long DEFAULT_MAX_REQ_TIME = -1; // default: forever
    private static final long DEFAULT_MAX_RSP_TIME = -1; // default: forever
    private static final long DEFAULT_TIMER_MILLIS = 1000;
    private static final int  DEFAULT_MAX_REQ_HEADERS = 200;
    private static final long DEFAULT_DRAIN_AMOUNT = 64 * 1024;

    private static int clockTick;
    private static long idleInterval;
    // The maximum number of bytes to drain from an inputstream
    private static long drainAmount;
    private static int maxIdleConnections;
    // The maximum number of request headers allowable
    private static int maxReqHeaders;
    // max time a request or response is allowed to take
    private static long maxReqTime;
    private static long maxRspTime;
    private static long timerMillis;
    private static boolean debug;

    // the value of the TCP_NODELAY socket-level option
    private static boolean noDelay;

    static boolean debugEnabled() {
        return debug;
    }

    public static long getIdleInterval() {
        return idleInterval;
    }

    public static int getClockTick() {
        return clockTick;
    }

    public static int getMaxIdleConnections() {
        return maxIdleConnections;
    }

    public static long getDrainAmount() {
        return drainAmount;
    }

    public static int getMaxReqHeaders() {
        return maxReqHeaders;
    }

    public static long getMaxReqTime() {
        return maxReqTime;
    }

    public static long getMaxRspTime() {
        return maxRspTime;
    }

    public static long getTimerMillis() {
        return timerMillis;
    }

    public static boolean noDelay() {
        return noDelay;
    }
}
