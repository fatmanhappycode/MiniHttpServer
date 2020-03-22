package com.fatman.minihttpserver.httpserver;

import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * @author 肥宅快乐码
 * @date 2020/3/20 - 11:52
 */
public class IoUtil {

    private static boolean isExist(String path) {
        File file = new File(path);
        return file.exists();
    }

    public static byte[] readFileByBytes(String path) {
        if (!isExist(path)) {
            return "".getBytes();
        }
        byte[] bytes = null;
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(path);
            FileChannel c = fis.getChannel();
            ByteBuffer bc = ByteBuffer.allocate((int) c.size());
            int i = c.read(bc);
            if (i != -1) {
                bytes = bc.array();
            }
            c.close();
            fis.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return bytes;
    }
}
