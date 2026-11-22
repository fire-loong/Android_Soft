package com.actionloop.api.web;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 缓存请求体字节，使幂等过滤器可计算 request_hash，同时控制器仍能再次读取 body。
 */
public class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

    private final byte[] body;

    public CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
        this(request, 1_048_576L);
    }

    public CachedBodyHttpServletRequest(HttpServletRequest request, long maxBytes) throws IOException {
        super(request);
        try (InputStream in = request.getInputStream()) {
            byte[] read = in.readNBytes((int) maxBytes + 1);
            if (read.length > maxBytes) {
                throw new IOException("request body exceeds " + maxBytes + " bytes");
            }
            this.body = read;
        }
    }

    public byte[] getCachedBody() {
        return body;
    }

    @Override
    public ServletInputStream getInputStream() {
        final ByteArrayInputStream bais = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public boolean isFinished() {
                return bais.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
            }

            @Override
            public int read() {
                return bais.read();
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }
}
