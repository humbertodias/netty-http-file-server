package netty;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.stream.ChunkedWriteHandler;

public class HttpFileServer {

    public static final String DEFAULT_URL = "/";

    public void run(int port, String url) throws Exception {
        EventLoopGroup boss = new NioEventLoopGroup();
        EventLoopGroup worker = new NioEventLoopGroup();

        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(boss, worker).channel(NioServerSocketChannel.class).childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) throws Exception {
                    ch.pipeline()
                    // Added http decoder
                    .addLast("http-decoder", new HttpRequestDecoder())
                    // If the ObjectAggregator decoder is used to convert multiple messages into a single FullHttpRequest or FullHttpResponse
                    .addLast("http-aggregator", new HttpObjectAggregator(65536))
                    // Add http decoder
                    .addLast("http-encoder", new HttpResponseEncoder())
                    // Added chunked to support asynchronously sent streams (large file transfers), but not take up too much memory and prevent jdk memory overflow
                    .addLast("http-chunked", new ChunkedWriteHandler())
                    // Add custom business server handlers
                    .addLast("fileServerHandler", new HttpFileServerHandler(url));
                }
            });
            ChannelFuture future = b.bind("127.0.0.1", port).sync();
            System.out.printf("HTTP file directory server starts： http://127.0.0.1:%s%s\n", port , url);
            future.channel().closeFuture().sync();
        } finally {
            boss.shutdownGracefully();
            worker.shutdownGracefully();
        }
    }

    public static void main(String[] args) throws Exception {
        int port = args.length == 0 ? 9999 : Integer.parseInt(args[0]);
        new HttpFileServer().run(port, DEFAULT_URL);
    }

}