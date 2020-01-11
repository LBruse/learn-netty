package io.netty.example.myTest.myhandler;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.example.myTest.myhandler.exception.BusinessExcepiton;

public class ExceptionCaughtHandler extends ChannelInboundHandlerAdapter {

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
//        在此处理所有未捕获异常
        if (cause instanceof BusinessExcepiton) {
            System.out.println("BusinessException");
        }
    }

}
