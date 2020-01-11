package io.netty.example.myTest.design;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.ByteProcessor;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

/**
 * 单例模式
 * @see io.netty.handler.timeout.ReadTimeoutException
 * @see io.netty.handler.codec.mqtt.MqttEncoder
 * 策略模式
 * @see io.netty.util.concurrent.DefaultEventExecutorChooserFactory#newChooser(EventExecutor[])
 * 装饰者模式
 * @see io.netty.buffer.WrappedByteBuf
 * @see io.netty.buffer.UnreleasableByteBuf
 * @see io.netty.buffer.SimpleLeakAwareByteBuf
 * 观察者模式
 * @see io.netty.channel.ChannelFuture
 * 迭代器模式
 * @see ByteBuf
 * 责任链模式
 * 创建链,添加删除责任处理器接口
 * @see io.netty.channel.ChannelPipeline
 * 责任处理器接口
 * @see io.netty.channel.ChannelHandler
 * @see io.netty.channel.ChannelInboundHandler
 * @see io.netty.channel.ChannelOutboundHandler
 * 上下文
 * @see io.netty.channel.ChannelHandlerContext
 * 责任终止机制
 */
public class Design {

    /**
     * 观察者模式体现
     * @param channel
     * @param o
     */
    public void write(NioSocketChannel channel,Object o){
        ChannelFuture channelFuture=channel.writeAndFlush(o);
        channelFuture.addListener(new GenericFutureListener<Future<? super Void>>() {
            @Override
            public void operationComplete(Future<? super Void> future) throws Exception {
                if(future.isSuccess()){
//                    do something
                }else{
//                    do something
                }
            }
        });
    }

    /**
     * 迭代器模式体现
     */
    public void iterable(){
        ByteBuf header= Unpooled.wrappedBuffer(new byte[]{1,2,3});
        header.forEachByte(new ByteProcessor() {
            @Override
            public boolean process(byte value) throws Exception {
                System.out.println(value);
                return false;
            }
        });
    }

}
