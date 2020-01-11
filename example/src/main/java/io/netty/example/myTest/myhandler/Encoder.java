package io.netty.example.myTest.myhandler;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.util.concurrent.EventExecutorGroup;

public class Encoder extends MessageToByteEncoder<User> {

    @Override
    protected void encode(ChannelHandlerContext ctx, User user, ByteBuf out) throws Exception {
        byte[] bytes = user.getName().getBytes();
//        length   4[age长度]+name.length
        out.writeInt(4 + bytes.length);
//        写入age
        out.writeInt(user.getAge());
//        写入name
        out.writeBytes(bytes);
    }

}
