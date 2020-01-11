package io.netty.example.myTest.myhandler;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

/**
 * 权限校验Handler
 */
public class AuthHandler extends SimpleChannelInboundHandler<ByteBuf> {

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf password) throws Exception {
//        密码通过,将自身从pipeline中进行移除
        if (paas(password)) {
            ctx.pipeline().remove(this);
        } else {
//            密码不通过,将NioSocketChannel关闭
            ctx.close();
        }
    }

    private boolean paas(ByteBuf password) {
        return false;
    }

}
