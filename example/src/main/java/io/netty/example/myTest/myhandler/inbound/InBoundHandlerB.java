package io.netty.example.myTest.myhandler.inbound;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.example.myTest.myhandler.exception.BusinessExcepiton;

public class InBoundHandlerB extends ChannelInboundHandlerAdapter {

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        System.out.println("inBoundHandlerB: " + msg);
//        从当前节点往下传播
//        ctx.fireChannelRead(msg);

        throw new BusinessExcepiton("from InBoundHandlerB");
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
//        从pipeline head节点开始往下传播
        ctx.channel().pipeline().fireChannelRead("hello world");
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        System.out.println("InBoundHandlerB.exceptionCaught()");
        ctx.fireExceptionCaught(cause);
    }
}
