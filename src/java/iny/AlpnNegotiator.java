package iny;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;

public class AlpnNegotiator extends ApplicationProtocolNegotiationHandler {

    public AlpnNegotiator() {
        super(ApplicationProtocolNames.HTTP_1_1);
    }

    @Override
    protected void configurePipeline(ChannelHandlerContext ctx, String proto) throws Exception {
        switch (proto) {
            case ApplicationProtocolNames.HTTP_2:
            case ApplicationProtocolNames.HTTP_1_1:
                ctx.fireUserEventTriggered(proto);
                break;
            default:
                ctx.fireUserEventTriggered(false);
        }
    }
}
