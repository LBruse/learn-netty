package io.netty.example.myTest.myhandler.outbound;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;

import java.util.concurrent.TimeUnit;

public class OutboundHandlerB extends ChannelOutboundHandlerAdapter {

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        System.out.println("OutBoundHandlerB " + msg);
        ctx.write(msg, promise);
    }

    @Override
    public void handlerAdded(final ChannelHandlerContext ctx) throws Exception {
        ctx.executor().schedule(new Runnable() {
            @Override
            public void run() {
//                pipeline.write()-->tail node.write()-->C.write()-->B.write()-->A.write()-->head node.write()
                ctx.channel().write("hello world");

//                直接从当前节点开始进行传播
//                ctx.write("hello world2");
            }
        }, 3, TimeUnit.SECONDS);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        System.out.println("OutBoundHandlerB.exceptionCaught");
        ctx.fireExceptionCaught(cause);
    }

}
