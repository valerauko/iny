package iny.http2;

import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.AbstractHttp2ConnectionHandlerBuilder;
import io.netty.handler.codec.http2.Http2ConnectionDecoder;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2FrameListener;
import io.netty.handler.codec.http2.Http2FrameLogger;
import io.netty.handler.codec.http2.Http2Settings;

import clojure.lang.IFn;

import static io.netty.handler.logging.LogLevel.INFO;

public final class HandlerBuilder
        extends AbstractHttp2ConnectionHandlerBuilder<Http2ConnectionHandler, HandlerBuilder> {

    private static final Http2FrameLogger logger = new Http2FrameLogger(INFO, Http2ConnectionHandler.class);

    private IFn builder;

    public HandlerBuilder(IFn func) {
        this.builder = func;
        // frameLogger(logger);
    }

    @Override
    public Http2ConnectionHandler build() {
        return super.build();
    }

    @Override
    protected Http2ConnectionHandler build(Http2ConnectionDecoder decoder, Http2ConnectionEncoder encoder,
                                           Http2Settings initialSettings) {
        Http2ConnectionHandler handler = (Http2ConnectionHandler) builder.invoke(decoder, encoder, initialSettings);
        frameListener((Http2FrameListener) handler);
        return handler;
    }
}
