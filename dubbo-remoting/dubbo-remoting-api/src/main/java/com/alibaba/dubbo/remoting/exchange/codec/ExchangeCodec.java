/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.dubbo.remoting.exchange.codec;

import com.alibaba.dubbo.common.Version;
import com.alibaba.dubbo.common.io.Bytes;
import com.alibaba.dubbo.common.io.StreamUtils;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.serialize.Cleanable;
import com.alibaba.dubbo.common.serialize.ObjectInput;
import com.alibaba.dubbo.common.serialize.ObjectOutput;
import com.alibaba.dubbo.common.serialize.Serialization;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.buffer.ChannelBuffer;
import com.alibaba.dubbo.remoting.buffer.ChannelBufferInputStream;
import com.alibaba.dubbo.remoting.buffer.ChannelBufferOutputStream;
import com.alibaba.dubbo.remoting.exchange.Request;
import com.alibaba.dubbo.remoting.exchange.Response;
import com.alibaba.dubbo.remoting.exchange.support.DefaultFuture;
import com.alibaba.dubbo.remoting.telnet.codec.TelnetCodec;
import com.alibaba.dubbo.remoting.transport.CodecSupport;
import com.alibaba.dubbo.remoting.transport.ExceedPayloadLimitException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * ExchangeCodec.
 *
 *
 *
 */
public class ExchangeCodec extends TelnetCodec {

    // header length.
    protected static final int HEADER_LENGTH = 16;
    // magic header.
    protected static final short MAGIC = (short) 0xdabb;
    protected static final byte MAGIC_HIGH = Bytes.short2bytes(MAGIC)[0];
    protected static final byte MAGIC_LOW = Bytes.short2bytes(MAGIC)[1];
    // message flag.
    protected static final byte FLAG_REQUEST = (byte) 0x80;
    protected static final byte FLAG_TWOWAY = (byte) 0x40;
    protected static final byte FLAG_EVENT = (byte) 0x20;
    protected static final int SERIALIZATION_MASK = 0x1f;
    private static final Logger logger = LoggerFactory.getLogger(ExchangeCodec.class);

    public Short getMagicCode() {
        return MAGIC;
    }

    @Override
    public void encode(Channel channel, ChannelBuffer buffer, Object msg) throws IOException {
        if (msg instanceof Request) {
            encodeRequest(channel, buffer, (Request) msg);
        } else if (msg instanceof Response) {
            encodeResponse(channel, buffer, (Response) msg);
        } else {
            super.encode(channel, buffer, msg);
        }
    }

    /**
     * 整体实现解码过程中要解决粘包和半包问题
     *
     * 在①中最多读取Dubbo报文头部(16字节)， 如果流中不足16字节，则会把流中数据读取完毕。在decode方法中会先判断流当前位置是不
     * 是Dubbo报文开始处，在流中判断报文分割点是通过②判断的(Oxdabb魔法数)。
     * 如果当前流中没有遇到完整Dubbo报文(在③中会判断流可读字节数)，在④中会为剩余可读流分配存储空间，
     * 在⑤中会将流中数据全部读取并追加在header数组中。当流被读取完后，会查找流中第一个Dubbo报文开始处的索引，
     * 在⑥中会将buffer索引指向流中第一个Dubbo报文开始处(Oxdabb)
     * 在⑦中主要将流中从起始位置(初始buffer的readerindex)到第一个Dubbo报文开始处的数据保存在header中，用于⑧解码header数据，目前常用的场景有Telnet调用等。
     *
     * 在正常场景中解析时，在⑨中首先判断当次读取的字节是否多于16字节，否则等待更多网
     * 络数据到来。在⑩中会判断Dubbo报文头部包含的消息体长度，然后校验消息体长度是否超过限制（默认为8MB 。在⑪中会校验这次解码能否处理整个报文。
     * 在⑫中处理消息体解码，这个是强协议相关的，因此Dubbo协议重写了这部分实现，
     *
     * @param channel
     * @param buffer
     * @return
     * @throws IOException
     */
    @Override
    public Object decode(Channel channel, ChannelBuffer buffer) throws IOException {
        int readable = buffer.readableBytes();
        // ① 最多读取16个字节，并分配存储空间
        byte[] header = new byte[Math.min(readable, HEADER_LENGTH)];
        buffer.readBytes(header);
        return decode(channel, buffer, readable, header);
    }

    @Override
    protected Object decode(Channel channel, ChannelBuffer buffer, int readable, byte[] header) throws IOException {
        // check magic number.
        // ②处理流起始处不是Dubbo魔法数Oxdabb 场景
        if (readable > 0 && header[0] != MAGIC_HIGH
                || readable > 1 && header[1] != MAGIC_LOW) {
            int length = header.length;
            // ③ 流中还有数据可以读取
            if (header.length < readable) {
                // ④为 header 重新分配空间，用来存储流中所有可读字节
                header = Bytes.copyOf(header, readable);
                // ⑤将流中剩余字节读取到header中
                buffer.readBytes(header, length, readable - length);
            }
            for (int i = 1; i < header.length - 1; i++) {
                if (header[i] == MAGIC_HIGH && header[i + 1] == MAGIC_LOW) {
                    // ⑥将buffer读索引指向回Dubbo报文开头处(Oxdabb)
                    buffer.readerIndex(buffer.readerIndex() - header.length + i);
                    // ⑦将流起始处至下一个Dubbo报文之间的数据放到header中
                    header = Bytes.copyOf(header, i);
                    break;
                }
            }
            // ⑧ 主要用于解析header数据，比如用于Telnet
            return super.decode(channel, buffer, readable, header);
        }
        // check length.
        // ⑨如果读取数据长度小于16个字节，则期待更多数据
        if (readable < HEADER_LENGTH) {
            return DecodeResult.NEED_MORE_INPUT;
        }

        // get data length.

        // 10 提取头部存储的报文长度，并校验长度是
        int len = Bytes.bytes2int(header, 12);
        checkPayload(channel, len);

        int tt = len + HEADER_LENGTH;
        // 11 校验是否可以读取完整Dubbo报文，否则期待更多数据
        if (readable < tt) {
            return DecodeResult.NEED_MORE_INPUT;
        }

        // limit input stream.
        ChannelBufferInputStream is = new ChannelBufferInputStream(buffer, len);

        try {
            // ⑫解码消息体，is流是完整的 RPC 调用报文
            return decodeBody(channel, is, header);
        } finally {
            // 13 如果解码过程有问题，则跳过这次RPC调用报文
            if (is.available() > 0) {
                try {
                    if (logger.isWarnEnabled()) {
                        logger.warn("Skip input stream " + is.available());
                    }
                    StreamUtils.skipUnusedStream(is);
                } catch (IOException e) {
                    logger.warn(e.getMessage(), e);
                }
            }
        }
    }

    /**
     * 解码请求报文
     * 站在解码器的角度，解码请求一定是通过标志判断类别的，否则不知道是请求还是响应， Dubbo报文16字节头部长度包含了 FLAG_REQUEST标志位。
     *
     * ①：根据这个标志位创建请求对象，
     * ②：在I/O线程中直接解码(比如在Netty的I/O线程中)，然后简单调用decode解码，解码逻
     * 辑在后面会详细探讨。③：实际上不做解码，延迟到业务线程池中解码。
     * ④：将解码消息体作为Rpclnvocation放到请求数据域中。如果解码失败了，则会通过⑤标记，并把异常原因记录下
     * 来。这里没有提到的是心跳和事件的解码，这两种解码非常简单，心跳报文是没有消息体的， 事件有消息体，在使用Hessian2协议的情况下默认会传递字符R,当优雅停机时会通过发送
     * readonly事件来通知客户端服务端不可用。
     *
     *
     * @param channel
     * @param is
     * @param header
     * @return
     * @throws IOException
     */
    protected Object decodeBody(Channel channel, InputStream is, byte[] header) throws IOException {
        byte flag = header[2], proto = (byte) (flag & SERIALIZATION_MASK);
        // get request id.
        long id = Bytes.bytes2long(header, 4);
        if ((flag & FLAG_REQUEST) == 0) {
            // decode response.
            Response res = new Response(id);
            if ((flag & FLAG_EVENT) != 0) {
                res.setEvent(Response.HEARTBEAT_EVENT);
            }
            // get status.
            byte status = header[3];
            res.setStatus(status);
            try {
                ObjectInput in = CodecSupport.deserialize(channel.getUrl(), is, proto);
                if (status == Response.OK) {
                    Object data;
                    if (res.isHeartbeat()) {
                        byte[] eventPayload = CodecSupport.getPayload(is);
                        data = decodeHeartbeatData(channel, CodecSupport.deserialize(channel.getUrl(), new ByteArrayInputStream(eventPayload), proto), eventPayload);
                    } else if (res.isEvent()) {
                        byte[] eventPayload = CodecSupport.getPayload(is);
                        data = decodeEventData(channel,
                                CodecSupport.deserialize(channel.getUrl(), new ByteArrayInputStream(eventPayload), proto), eventPayload);
                    } else {
                        data = decodeResponseData(channel, in, getRequestData(id));
                    }
                    res.setResult(data);
                } else {
                    res.setErrorMessage(in.readUTF());
                }
            } catch (Throwable t) {
                res.setStatus(Response.CLIENT_ERROR);
                res.setErrorMessage(StringUtils.toString(t));
            }
            return res;
        } else {
            // decode request.
            // ① 请求标志位被设置，创建Request对象
            Request req = new Request(id);
            req.setVersion(Version.getProtocolVersion());
            req.setTwoWay((flag & FLAG_TWOWAY) != 0);
            if ((flag & FLAG_EVENT) != 0) {
                req.setEvent(Request.HEARTBEAT_EVENT);
            }
            try {
                ObjectInput in = CodecSupport.deserialize(channel.getUrl(), is, proto);
                Object data;
                if (req.isHeartbeat()) {
                    byte[] eventPayload = CodecSupport.getPayload(is);
                    data = decodeHeartbeatData(channel,
                            CodecSupport.deserialize(channel.getUrl(), new ByteArrayInputStream(eventPayload), proto), eventPayload);
                } else if (req.isEvent()) {
                    byte[] eventPayload = CodecSupport.getPayload(is);
                    data = decodeEventData(channel,
                            CodecSupport.deserialize(channel.getUrl(), new ByteArrayInputStream(eventPayload), proto), eventPayload);
                } else {
                    // ② 3  解码
                    data = decodeRequestData(channel, in);
                }
                // ④ 将 Rpclnvocation 作为 Request 的数据域
                req.setData(data);
            } catch (Throwable t) {
                // bad request
                // ⑤解码失败，先做标记并存储异常
                req.setBroken(true);
                req.setData(t);
            }
            return req;
        }
    }

    protected Object getRequestData(long id) {
        DefaultFuture future = DefaultFuture.getFuture(id);
        if (future == null)
            return null;
        Request req = future.getRequest();
        if (req == null)
            return null;
        return req.getData();
    }

    /**
     * 请求对象编码，主要职责是将Dubbo请求对象编码成字节流(包括协议报文头部)。
     *
     * 在①中主要提取URL中配置的序列化协议或默认协议。
     * 在②中会创建16字节的报文头部。
     * 在③中首先会将魔法数写入头部并占用2个字节。
     * 在④中主要设置请求标识和消息体中使用的序列化协议。
     * 在⑤中会复用同一个字节，标记这个请求需要服务端返回。
     * 在⑥中主要承载请求的唯一标识，这个标识用于匹配响应的数据。
     * 在⑦中会在buffer中预留16字节存储头部，
     * 在⑧中会序列化请求部分，比如方法名等信息，后面会讲解。
     * 在⑨中会检查编码后的报文是否超过大小限制(默认是8MB)。
     * 在⑩中将消息体长度写入头部偏移量(第12个字节)，长度占用4个字节。
     * 在⑪中将buffer定位到报文头部开始，
     * 在⑫中将构造好的头部写入buffer。
     * 在⑬中再将buffer写入索引执行消息体结尾的下一个位置。
     *
     * 在⑧中会调用encodeRequestData方法对Rpclnvocation调用进行编码，
     * 这部分主要就是对接口、方法、 方法参数类型、方法参数等进行编码，在DubboCodec#encodeRequestData中重写了这个方法实现
     *
     * @param channel
     * @param buffer
     * @param req
     * @throws IOException
     */
    protected void encodeRequest(Channel channel, ChannelBuffer buffer, Request req) throws IOException {
        // ①获取指定或默认的序列化协议(Hessian2)
        Serialization serialization = getSerialization(channel);
        // header.
        // ② 构造 16 字节头
        byte[] header = new byte[HEADER_LENGTH];
        // set magic number.
        // ③ 占用 2 个字节存储魔法数
        Bytes.short2bytes(MAGIC, header);

        // set request and serialization flag.
        // 在第3个字节(16位和19〜23位)分别存储请求标志和序列化协议序号
        header[2] = (byte) (FLAG_REQUEST | serialization.getContentTypeId());

        // ⑤设置请求/响应标记
        if (req.isTwoWay()) header[2] |= FLAG_TWOWAY;
        if (req.isEvent()) header[2] |= FLAG_EVENT;

        // set request id.
        // ⑥设置请求唯一标识
        Bytes.long2bytes(req.getId(), header, 4);

        // encode request data.
        int savedWriteIndex = buffer.writerIndex();
        // ⑦ 跳过buffer头部16个字节， 用于序列化消息体
        buffer.writerIndex(savedWriteIndex + HEADER_LENGTH);
        ChannelBufferOutputStream bos = new ChannelBufferOutputStream(buffer);
        ObjectOutput out = serialization.serialize(channel.getUrl(), bos);
        if (req.isEvent()) {
            encodeEventData(channel, out, req.getData());
        } else {
            // ⑧ 序列化请求调用,data —般是Rpclnvocation
            encodeRequestData(channel, out, req.getData(), req.getVersion());
        }
        out.flushBuffer();
        if (out instanceof Cleanable) {
            ((Cleanable) out).cleanup();
        }
        bos.flush();
        bos.close();
        int len = bos.writtenBytes();

        // ⑨检查是否超过默认8MB大小
        checkPayload(channel, len);
        // ⑩向消息长度写入头部第12个字节的偏移量(96〜127位)
        Bytes.int2bytes(len, header, 12);

        // write
        // ⑪定位指针到报文头部开始位
        buffer.writerIndex(savedWriteIndex);
        // 12 写入完整报文头部到 buffer
        buffer.writeBytes(header); // write header.
        // 13 设置writerindex到消息体结束位置
        buffer.writerIndex(savedWriteIndex + HEADER_LENGTH + len);
    }

    /**
     * 编码响应对象,主要职责是将Dubbo响应对象编码成字节流(包括协议报文头部)。
     *
     *
     * 在①中获取用户指定或默认的序列化协议，
     * 在②中构造报文头部(16字节)。
     * 在③中同样将魔法数填入报文头部前2个字节。
     * 在④中会将服务端配置的序列化协议写入头部。
     * 在⑤中报文头部中status会保存服务端调用状态码。
     * 在⑥中会将请求唯一 id设置回响应头中。
     * 在⑦中空出16字节头部用于存储响应体报文。
     * 在⑧中会对服务端调用结果进行编码，后面会进行详细解释。
     * 在⑨中主要对响应报文大小做检查，默认校验是否超过8MB大小。
     * 在⑩中将消息体长度写入头部偏移量(第12个字节)，长度占用4个字节。
     * 在⑪中将buffer定位到报文头部开始，
     * 在⑫中将构造好的头部写入buffer。
     * 在⑬中再将buffer写入索引执行消息体结尾的下一个位置。
     * 在⑭中主要处理编码报错复位buffer,否则导致缓冲区中数据错乱。
     * 在⑥中会将异常响应返回到客户端，防止客户端只有等到超时才能感知服务调用返回。
     * 在⑯和⑰中主要对报错进行了细分，处理服务端报文超过限制和具体报错原因。为了防止报错对象无法在客户端反序列化，在
     * 服务端会将异常信息转成字符串处理。
     *
     *
     * ⑧中处理响应，具体实现在DubboCodec#encodeResponseData中
     * @param channel
     * @param buffer
     * @param res
     * @throws IOException
     */
    protected void encodeResponse(Channel channel, ChannelBuffer buffer, Response res) throws IOException {
        int savedWriteIndex = buffer.writerIndex();
        try {
            // ①获取指定或默认的序列化协议(Hessian2 ) I
            Serialization serialization = getSerialization(channel);
            // header. ② 构造 16 字节头
            byte[] header = new byte[HEADER_LENGTH];
            // set magic number.  ③ 占用2个字节存储魔法数
            Bytes.short2bytes(MAGIC, header);
            // set request and serialization flag. ④在第3个字节（19〜23位）存储响应标志
            header[2] = serialization.getContentTypeId();
            if (res.isHeartbeat()) header[2] |= FLAG_EVENT;
            // set response status. ⑤ 在第4个字节存储响应状态
            byte status = res.getStatus();
            header[3] = status;
            // set request id. ⑥设置请求唯一标识
            Bytes.long2bytes(res.getId(), header, 4);

            // ⑦空出16字节头部用于存储响应体报文
            buffer.writerIndex(savedWriteIndex + HEADER_LENGTH);
            ChannelBufferOutputStream bos = new ChannelBufferOutputStream(buffer);
            ObjectOutput out = serialization.serialize(channel.getUrl(), bos);
            // encode response data or error message.
            if (status == Response.OK) {
                if (res.isHeartbeat()) {
                    encodeHeartbeatData(channel, out, res.getResult());
                } else {
                    // ⑧序列化响应调用，data-般是 Result对象
                    encodeResponseData(channel, out, res.getResult(), res.getVersion());
                }
            } else out.writeUTF(res.getErrorMessage());
            out.flushBuffer();
            if (out instanceof Cleanable) {
                ((Cleanable) out).cleanup();
            }
            bos.flush();
            bos.close();

            // ⑨检查是否超过默认的8MB大小
            int len = bos.writtenBytes();
            checkPayload(channel, len);

            // ⑩向消息长度写入头部第12个字节偏移量(96 ~ 127 位)
            Bytes.int2bytes(len, header, 12);
            // write 一 11 定位指针到报文头部开始位置
            buffer.writerIndex(savedWriteIndex);
            // — 12 写入完整报文头部到 buffer
            buffer.writeBytes(header); // write header.
            // 13 设置writerindex到消息体结束位置|
            buffer.writerIndex(savedWriteIndex + HEADER_LENGTH + len);
        } catch (Throwable t) {
            // clear buffer — 14 如果编码失败，则复位 buffer
            buffer.writerIndex(savedWriteIndex);
            // send error message to Consumer, otherwise, Consumer will wait till timeout.
            // 15 将编码响应异常发送给consumer,否则只能等待到超时
            if (!res.isEvent() && res.getStatus() != Response.BAD_RESPONSE) {
                Response r = new Response(res.getId(), res.getVersion());
                r.setStatus(Response.BAD_RESPONSE);

                if (t instanceof ExceedPayloadLimitException) {
                    logger.warn(t.getMessage(), t);
                    try {
                        r.setErrorMessage(t.getMessage());
                        // 16 告知客户端数据包长度超过限制
                        channel.send(r);
                        return;
                    } catch (RemotingException e) {
                        logger.warn("Failed to send bad_response info back: " + t.getMessage() + ", cause: " + e.getMessage(), e);
                    }
                } else {
                    // FIXME log error message in Codec and handle in caught() of IoHanndler?
                    logger.warn("Fail to encode response: " + res + ", send bad_response info instead, cause: " + t.getMessage(), t);
                    try {
                        // 17 告知客户端编码失败的具体原因
                        r.setErrorMessage("Failed to send response: " + res + ", cause: " + StringUtils.toString(t));
                        channel.send(r);
                        return;
                    } catch (RemotingException e) {
                        logger.warn("Failed to send bad_response info back: " + res + ", cause: " + e.getMessage(), e);
                    }
                }
            }

            // Rethrow exception
            if (t instanceof IOException) {
                throw (IOException) t;
            } else if (t instanceof RuntimeException) {
                throw (RuntimeException) t;
            } else if (t instanceof Error) {
                throw (Error) t;
            } else {
                throw new RuntimeException(t.getMessage(), t);
            }
        }
    }

    @Override
    protected Object decodeData(ObjectInput in) throws IOException {
        return decodeRequestData(in);
    }

    protected Object decodeRequestData(ObjectInput in) throws IOException {
        try {
            return in.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException(StringUtils.toString("Read object failed.", e));
        }
    }

    protected Object decodeResponseData(ObjectInput in) throws IOException {
        try {
            return in.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException(StringUtils.toString("Read object failed.", e));
        }
    }

    @Override
    protected void encodeData(ObjectOutput out, Object data) throws IOException {
        encodeRequestData(out, data);
    }

    private void encodeEventData(ObjectOutput out, Object data) throws IOException {
        out.writeObject(data);
    }

    @Deprecated
    protected void encodeHeartbeatData(ObjectOutput out, Object data) throws IOException {
        encodeEventData(out, data);
    }

    protected void encodeRequestData(ObjectOutput out, Object data) throws IOException {
        out.writeObject(data);
    }

    protected void encodeResponseData(ObjectOutput out, Object data) throws IOException {
        out.writeObject(data);
    }

    @Override
    protected Object decodeData(Channel channel, ObjectInput in) throws IOException {
        return decodeRequestData(channel, in);
    }

    protected Object decodeEventData(Channel channel, ObjectInput in, byte[] eventPayload) throws IOException {
        try {
            int dataLen = eventPayload.length;
            int threshold = Integer.parseInt(System.getProperty("deserialization.event.size", "50"));
            if (dataLen > threshold) {
                throw new IllegalArgumentException("Event data too long, actual size " + dataLen + ", threshold " + threshold + " rejected for security consideration.");
            }
            return in.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException(StringUtils.toString("Read object failed.", e));
        }
    }

    @Deprecated
    protected Object decodeHeartbeatData(Channel channel, ObjectInput in, byte[] eventPayload) throws IOException {
        return decodeEventData(channel, in, eventPayload);
    }

    protected Object decodeRequestData(Channel channel, ObjectInput in) throws IOException {
        return decodeRequestData(in);
    }

    protected Object decodeResponseData(Channel channel, ObjectInput in) throws IOException {
        return decodeResponseData(in);
    }

    protected Object decodeResponseData(Channel channel, ObjectInput in, Object requestData) throws IOException {
        return decodeResponseData(channel, in);
    }

    @Override
    protected void encodeData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeRequestData(channel, out, data);
    }

    private void encodeEventData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeEventData(out, data);
    }

    @Deprecated
    protected void encodeHeartbeatData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeHeartbeatData(out, data);
    }

    protected void encodeRequestData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeRequestData(out, data);
    }

    protected void encodeResponseData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeResponseData(out, data);
    }

    protected void encodeRequestData(Channel channel, ObjectOutput out, Object data, String version) throws IOException {
        encodeRequestData(out, data);
    }

    protected void encodeResponseData(Channel channel, ObjectOutput out, Object data, String version) throws IOException {
        encodeResponseData(out, data);
    }


}
