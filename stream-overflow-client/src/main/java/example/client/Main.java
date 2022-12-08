package example.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.rsocket.metadata.TaggingMetadataCodec;
import io.rsocket.metadata.WellKnownMimeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        InetSocketAddress address = new InetSocketAddress("localhost", 7000);
        Bootstrap bootstrap = new Bootstrap()
                .group(new NioEventLoopGroup())
                .channel(NioSocketChannel.class)
                .handler(
                        new ChannelInitializer<SocketChannel>() {
                            @Override
                            protected void initChannel(SocketChannel ch) {
                                ch.pipeline().addLast(new RSocketStreamOverflowHandler());
                            }
                        });

        ChannelFuture connect = bootstrap.connect(address);
        connect.awaitUninterruptibly();
        logger.info("connected to {}", address);
        connect.channel().closeFuture().awaitUninterruptibly();
    }

    private static final class RSocketStreamOverflowHandler extends ChannelDuplexHandler {
        private static final byte[] METADATA_TYPE = "message/x.rsocket.composite-metadata.v0".getBytes(StandardCharsets.UTF_8);
        private static final byte[] DATA_TYPE = "application/cbor".getBytes(StandardCharsets.UTF_8);

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            super.channelActive(ctx);

            /*stop reading socket*/
            ctx.channel().config().setAutoRead(false);

            ByteBufAllocator allocator = ctx.alloc();

            /*setup frame*/

            ByteBuf setupFrame = allocator.buffer();
            setupFrame/*streamId*/
                    .writeInt(0)
                    /*flags*/
                    .writeShort(/*FrameType.SETUP*/0x01 << 10)
                    /*version*/
                    .writeInt(1 << 16)
                    /*keep-alive interval*/
                    .writeInt(100_000)
                    /*keep-alive timeout*/
                    .writeInt(1_000_000)
                    /*metadata type*/
                    .writeByte(METADATA_TYPE.length).writeBytes(METADATA_TYPE)
                    /*data type*/
                    .writeByte(DATA_TYPE.length).writeBytes(DATA_TYPE);

            ByteBuf setupLengthPrefix = encodeLength(allocator, setupFrame.readableBytes());

            ctx.write(setupLengthPrefix);
            ctx.writeAndFlush(setupFrame);

            /*request-stream frame*/

            /*spring's RSocket request metadata*/
            CompositeByteBuf metadata = allocator.compositeBuffer();
            ByteBuf routeMetadata = TaggingMetadataCodec.createRoutingMetadata(
                    allocator, Collections.singletonList("stream")).getContent();

            io.rsocket.metadata.CompositeMetadataCodec.encodeAndAddMetadata(metadata, allocator,
                    WellKnownMimeType.MESSAGE_RSOCKET_ROUTING, routeMetadata);

            ByteBuf metadataLengthPrefix = encodeLength(allocator, metadata.readableBytes());

            /*request data*/
            StringBuilder content = new StringBuilder();
            for (int i = 0; i < 100; i++) {
                content.append("stream-overflow");
            }
            Request request = new Request();
            request.setMessage(content.toString());
            /*CBOR message encoding*/
            ObjectMapper mapper = new CBORMapper();

            ByteBuf requestFrame = allocator.buffer();
            requestFrame/*streamId*/
                    .writeInt(1)
                    /*flags*/
                    .writeShort(/*FrameType.REQUEST_STREAM*/0x06 << 10 | /*metadata*/ 1 << 8)
                    /*requestN*/
                 .writeInt(42);
            ByteBuf data = Unpooled.wrappedBuffer(mapper.writeValueAsBytes(request));

            ByteBuf requestLengthPrefix = encodeLength(allocator,
                    requestFrame.readableBytes() + metadataLengthPrefix.readableBytes() +
                    metadata.readableBytes() + data.readableBytes());

            ctx.write(requestLengthPrefix);
            ctx.write(requestFrame);
            ctx.write(metadataLengthPrefix);
            ctx.write(metadata);
            ctx.writeAndFlush(data);
            logger.info("==> write REQUEST-STREAM frame with {} bytes of data payload", data.readableBytes());

            int batchSize = 1000;
            int batchInterval = 100;
            int requestN = 42;
            logger.info("==> stop reads, write REQUEST_N({}) frame batches of size {} every {} millis",
                    requestN, batchSize, batchInterval);
            /*send stream requestN periodically, at arbitrarily high frequency*/
            ctx.channel().eventLoop().scheduleAtFixedRate(() -> {
                for (int i = 0; i < batchSize; i++) {
                    /*requestN frame*/
                    ByteBuf requestNFrame = allocator.buffer();
                    requestNFrame/*streamId*/
                            .writeInt(1)
                            /*flags*/
                            .writeShort(/*FrameType.REQUEST_N*/0x08 << 10)
                            /*requestN*/
                            .writeInt(requestN);
                    ByteBuf requestNLengthPrefix = encodeLength(allocator, requestNFrame.readableBytes());

                    ctx.write(requestNLengthPrefix);
                    ctx.writeAndFlush(requestNFrame);
                    if (!ctx.channel().isWritable()) {
                        ctx.flush();
                    }
                }
            }, 0, batchInterval, TimeUnit.MILLISECONDS);
        }

        private ByteBuf encodeLength(ByteBufAllocator allocator, int length) {
            ByteBuf lengthPrefix = allocator.buffer(3);
            lengthPrefix.writeByte(length >> 16);
            lengthPrefix.writeByte(length >> 8);
            lengthPrefix.writeByte(length);
            return lengthPrefix;
        }
    }

    public static class Request {
        private String message;

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }
}
