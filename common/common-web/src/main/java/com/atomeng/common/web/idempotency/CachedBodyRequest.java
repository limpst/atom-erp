package com.atomeng.common.web.idempotency;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** 요청 본문을 미리 읽어(해시 계산) 컨트롤러가 다시 읽을 수 있게 하는 래퍼. */
final class CachedBodyRequest extends HttpServletRequestWrapper {

    private final byte[] body;

    /** 본문이 상한을 넘을 때 */
    static final class TooLargeException extends IOException {
        TooLargeException(long limit) {
            super("request body exceeds " + limit + " bytes");
        }
    }

    CachedBodyRequest(HttpServletRequest request, long maxBytes) throws IOException {
        super(request);
        long declared = request.getContentLengthLong();
        if (declared > maxBytes) {
            throw new TooLargeException(maxBytes);
        }
        byte[] read = request.getInputStream().readNBytes((int) Math.min(Integer.MAX_VALUE - 8, maxBytes + 1));
        if (read.length > maxBytes) {
            throw new TooLargeException(maxBytes); // Content-Length 없이(chunked) 보낸 큰 본문
        }
        this.body = read;
    }

    byte[] body() {
        return body;
    }

    @Override
    public ServletInputStream getInputStream() {
        var in = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public boolean isFinished() {
                return in.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException();
            }

            @Override
            public int read() {
                return in.read();
            }

            @Override
            public int read(byte[] b, int off, int len) {
                return in.read(b, off, len);
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }
}
