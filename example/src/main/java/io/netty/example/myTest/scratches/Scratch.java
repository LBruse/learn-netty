package io.netty.example.myTest.scratches;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;

public class Scratch {

    public static void main(String[] args) {

//        Page分配调试
        int page = 1024 * 8;
        PooledByteBufAllocator allocator = PooledByteBufAllocator.DEFAULT;
        allocator.directBuffer(2 * page);

//        Subpage分配调试
        PooledByteBufAllocator allocator2 = PooledByteBufAllocator.DEFAULT;
//        16字节
        allocator2.directBuffer(16);

//        ByteBuffer回收调试
        PooledByteBufAllocator allocator3 = PooledByteBufAllocator.DEFAULT;
        ByteBuf byteBuf = allocator3.directBuffer(16);
//        AbstractReferenceCountedByteBuf
        byteBuf.release();
    }

}
