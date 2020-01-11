package io.netty.example.myTest;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.example.myTest.myhandler.BizHandler;
import io.netty.example.myTest.myhandler.Encoder;
import io.netty.example.myTest.myhandler.ExceptionCaughtHandler;
import io.netty.example.myTest.myhandler.inbound.InBoundHandlerA;
import io.netty.example.myTest.myhandler.inbound.InBoundHandlerB;
import io.netty.example.myTest.myhandler.inbound.InBoundHandlerC;
import io.netty.example.myTest.myhandler.outbound.OutboundHandlerA;
import io.netty.example.myTest.myhandler.outbound.OutboundHandlerB;
import io.netty.example.myTest.myhandler.outbound.OutboundHandlerC;
import io.netty.util.AttributeKey;

/**
 * Server
 */
public class Server {

    public static void main(String[] args) throws InterruptedException {
        EventLoopGroup boosGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(boosGroup, workerGroup)
//                    设置服务端SocketChannel
                    .channel(NioServerSocketChannel.class)
//                    给每一个客户端连接设置一些TCP的基本属性
                    .childOption(ChannelOption.TCP_NODELAY, true)
//                    每次创建客户端连接时绑定一些基本属性
                    .childAttr(AttributeKey.newInstance("childAttr"), "childAttrValue")
//                    服务端启动过程逻辑
                    .handler(new ServerHandler())
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) throws Exception {
//                            ch.pipeline().addLast(new ChannelInboundHandlerAdapter());
//                            ch.pipeline().addLast(new ChannelOutboundHandlerAdapter());
//                            ch.pipeline().addLast(new AuthHandler());

//                            ----------------------------------------
                            ch.pipeline().addLast(new InBoundHandlerA());
                            ch.pipeline().addLast(new InBoundHandlerB());
                            ch.pipeline().addLast(new InBoundHandlerC());

//                            ----------------------------------------
                            ch.pipeline().addLast(new OutboundHandlerA());
                            ch.pipeline().addLast(new OutboundHandlerB());
                            ch.pipeline().addLast(new OutboundHandlerC());

                            ch.pipeline().addLast(new ExceptionCaughtHandler());

//                            ----------------------------------------
                            ch.pipeline().addLast(new Encoder());
                            ch.pipeline().addLast(new BizHandler());
                        }
                    });

            ChannelFuture channelFuture = bootstrap.bind(8888).sync();
            channelFuture.channel().closeFuture().sync();
        } finally {
            boosGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }

    }

}
