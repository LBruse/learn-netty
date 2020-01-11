/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.handler.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.ByteProcessor;

import java.util.List;

/**
 * A decoder that splits the received {@link ByteBuf}s on line endings.
 * <p>
 * Both {@code "\n"} and {@code "\r\n"} are handled.
 * For a more general delimiter-based decoder, see {@link DelimiterBasedFrameDecoder}.
 */
public class LineBasedFrameDecoder extends ByteToMessageDecoder {

//    数据包最大长度,超出这个长度,可能会进入丢弃模式
    /** Maximum length of a frame we're willing to decode.  */
    private final int maxLength;
//    超出最大长度时是否立即抛出异常 true:立即 false:稍后
    /** Whether or not to throw an exception as soon as we exceed maxLength. */
    private final boolean failFast;
    /** 解析出来的数据包是否需要带换行符  true:不带换行符  false:带换行符 */
    private final boolean stripDelimiter;

//    丢弃模式标志位
    /** True if we're discarding input because we're already over maxLength.  */
    private boolean discarding;
    /** 已丢弃字节数量 */
    private int discardedBytes;

    /**
     * Creates a new decoder.
     * @param maxLength  the maximum length of the decoded frame.
     *                   A {@link TooLongFrameException} is thrown if
     *                   the length of the frame exceeds this value.
     */
    public LineBasedFrameDecoder(final int maxLength) {
        this(maxLength, true, false);
    }

    /**
     * Creates a new decoder.
     * @param maxLength  the maximum length of the decoded frame.
     *                   A {@link TooLongFrameException} is thrown if
     *                   the length of the frame exceeds this value.
     * @param stripDelimiter  whether the decoded frame should strip out the
     *                        delimiter or not
     * @param failFast  If <tt>true</tt>, a {@link TooLongFrameException} is
     *                  thrown as soon as the decoder notices the length of the
     *                  frame will exceed <tt>maxFrameLength</tt> regardless of
     *                  whether the entire frame has been read.
     *                  If <tt>false</tt>, a {@link TooLongFrameException} is
     *                  thrown after the entire frame that exceeds
     *                  <tt>maxFrameLength</tt> has been read.
     */
    public LineBasedFrameDecoder(final int maxLength, final boolean stripDelimiter, final boolean failFast) {
        this.maxLength = maxLength;
        this.failFast = failFast;
        this.stripDelimiter = stripDelimiter;
    }

    @Override
    protected final void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        Object decoded = decode(ctx, in);
        if (decoded != null) {
            out.add(decoded);
        }
    }

    /**
     * Create a frame out of the {@link ByteBuf} and return it.
     *
     * @param   ctx             the {@link ChannelHandlerContext} which this {@link ByteToMessageDecoder} belongs to
     * @param   buffer          the {@link ByteBuf} from which to read data
     * @return  frame           the {@link ByteBuf} which represent the frame or {@code null} if no frame could
     *                          be created.
     */
    protected Object decode(ChannelHandlerContext ctx, ByteBuf buffer) throws Exception {
//        找到行的结尾 \n  \r\n
        final int eol = findEndOfLine(buffer);
//        非丢弃模式
        if (!discarding) {
//            已找到换行符
            if (eol >= 0) {
                final ByteBuf frame;
//                从换行符到可读字节之间的长度
                final int length = eol - buffer.readerIndex();
//                分隔符长度  \r\n 长度为2  \n 长度为1
                final int delimLength = buffer.getByte(eol) == '\r'? 2 : 1;

//                读入数据长度>可读入数据包最大长度
                if (length > maxLength) {
//                    把readerIndex指向换行符后的字节(丢弃数据)
                    buffer.readerIndex(eol + delimLength);
//                    向下传播异常
                    fail(ctx, length);
                    return null;
                }
//                解析出来的数据包是否需要包含换行符
                if (stripDelimiter) {
//                    取出length长度数据包
                    frame = buffer.readRetainedSlice(length);
//                    跳过分隔符
                    buffer.skipBytes(delimLength);
                } else {
//                    不跳过换行符,即读取数据包含换行符
                    frame = buffer.readRetainedSlice(length + delimLength);
                }

                return frame;
            } else {
//                非丢弃模式
//                没有找到换行符
//                获取ByteBuf可读取数据长度
                final int length = buffer.readableBytes();
//                可读取数据长度>最大可读入数据长度
                if (length > maxLength) {
//                    将discardedBytes标记为ByteBuf可读取数据长度,表示这段数据要丢弃
                    discardedBytes = length;
//                    将ByteBuf读指针移动到ByteBuf写指针
                    buffer.readerIndex(buffer.writerIndex());
//                    标记discarding为true,当前进入丢弃模式
                    discarding = true;
//                    超出最大长度时立即抛出异常
                    if (failFast) {
//                        传播异常
                        fail(ctx, "over " + discardedBytes);
                    }
                }
//                可读取数据长度<最大可读入数据长度,return null,说明这一次没解析到任何数据
                return null;
            }
        } else {
//            丢弃模式
//            找到换行符
            if (eol >= 0) {
//                计算当前丢弃字节长度 (discardedBytes[前面已丢弃的字节])+(eol-buffer.readerIndex()[这次要丢弃的字节])
                final int length = discardedBytes + eol - buffer.readerIndex();
//                计算换行符长度是多少 \r\n为2 \n为1
                final int delimLength = buffer.getByte(eol) == '\r'? 2 : 1;
//                将读指针移动到换行符之后的位置
                buffer.readerIndex(eol + delimLength);
//                将丢弃字节数标记为0
                discardedBytes = 0;
//                恢复成非丢弃模式
                discarding = false;
//                超出最大长度时不立即抛出异常(只有在把所有字节都丢弃后才抛出异常)
                if (!failFast) {
//                    抛出异常
                    fail(ctx, length);
                }
            } else {
//                已丢弃字节+=当前可读字节
                discardedBytes += buffer.readableBytes();
//                把读指针移动到写指针
                buffer.readerIndex(buffer.writerIndex());
            }
            return null;
        }
    }

    private void fail(final ChannelHandlerContext ctx, int length) {
        fail(ctx, String.valueOf(length));
    }

    private void fail(final ChannelHandlerContext ctx, String length) {
        ctx.fireExceptionCaught(
                new TooLongFrameException(
                        "frame length (" + length + ") exceeds the allowed maximum (" + maxLength + ')'));
    }

    /**
     * Returns the index in the buffer of the end of line found.
     * Returns -1 if no end of line was found in the buffer.
     */
    private static int findEndOfLine(final ByteBuf buffer) {
//        找到\n的位置
        int i = buffer.forEachByte(ByteProcessor.FIND_LF);
//        如果\n前面是\r,指向\r的位置
        if (i > 0 && buffer.getByte(i - 1) == '\r') {
            i--;
        }
//        返回换行符位置
        return i;
    }
}
