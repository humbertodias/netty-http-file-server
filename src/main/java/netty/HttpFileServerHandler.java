package netty;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.stream.ChunkedFile;
import io.netty.util.CharsetUtil;

import javax.activation.MimetypesFileTypeMap;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.RandomAccessFile;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Pattern;

import static io.netty.handler.codec.http.HttpHeaderNames.*;
import static io.netty.handler.codec.http.HttpResponseStatus.*;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

public class HttpFileServerHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    // Whether the file is allowed to access download verification
    private static final Pattern ALLOWED_FILE_NAME = Pattern.compile("[A-Za-z0-9][-_A-Za-z0-9\\.]*");
    // Illegal URI regular
    private static final Pattern INSECURE_URI = Pattern.compile(".*[<>&\"].*");
    public static final String HTTP_DATE_FORMAT = "EEE, dd MMM yyyy HH:mm:ss zzz";
    public static final String HTTP_DATE_GMT_TIMEZONE = "GMT";
    public static final int HTTP_CACHE_SECONDS = 60;

    private String url;

    public HttpFileServerHandler(String url) {
        this.url = url;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        // Judging the decoding result of the request
        if (!request.decoderResult().isSuccess()) {
            // 400
            sendError(ctx, BAD_REQUEST);
            return;
        }

        // Judge the request method: if it is not GET method (or POST method), return an exception
        if (request.method() != HttpMethod.GET) {
            // 405
            sendError(ctx, METHOD_NOT_ALLOWED);
            return;
        }

        // Get the requested uri path
        String uri = request.uri();
        // Analyze the url and return to the local system
        String path = sanitizeUri(uri);
        // Path is null if the path construction is illegal
        if (path == null) {
            // 403
            sendError(ctx, FORBIDDEN);
            return;
        }

        // Create file object
        File file = new File(path);
        // Determine if the file is hidden or does not exist
        if (file.isHidden() || !file.exists()) {
            // 404
            sendError(ctx, NOT_FOUND);
            return;
        }

        // If it is a folder
        if (file.isDirectory()) {
            if (uri.endsWith("/")) {
                // If you access a file directory with a normal "/", display the file list
                sendListing(ctx, file);
            } else {
                //If not ending with "/", redirect, complete "/", and request again
                sendRedirect(ctx, uri + '/');
            }
            return;
        }
        // If the created file object is not a file type
        if (!file.isFile()) {
            // 403
            sendError(ctx, FORBIDDEN);
            return;
        }

        // Cache Validation
        String ifModifiedSince = request.headers().get(IF_MODIFIED_SINCE);
        if (ifModifiedSince != null && !ifModifiedSince.isEmpty()) {
            DateFormat dateFormatter = new SimpleDateFormat(HTTP_DATE_FORMAT, Locale.US);
            Date ifModifiedSinceDate = dateFormatter.parse(ifModifiedSince);
            // Only compare up to the second because the datetime format we send
            // to the client
            // does not have milliseconds
            long ifModifiedSinceDateSeconds = ifModifiedSinceDate.getTime() / 1000;
            long fileLastModifiedSeconds = file.lastModified() / 1000;
            if (ifModifiedSinceDateSeconds == fileLastModifiedSeconds) {
                sendNotModified(ctx);
                return;
            }
        }
        // Random file read and write classes
        RandomAccessFile raf;
        try {
            // Open file as read-only
            raf = new RandomAccessFile(file, "r");
        } catch (FileNotFoundException ignore) {
            // 404
            sendError(ctx, NOT_FOUND);
            return;
        }
        long fileLength = raf.length();
        HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        // Set response message
        HttpUtil.setContentLength(response, fileLength);
        // Set response header
        setContentTypeHeader(response, file);
        setDateAndCacheHeaders(response, file);
        if (HttpUtil.isKeepAlive(request)) {
            response.headers().set("CONNECTION", HttpHeaderValues.KEEP_ALIVE);
        }

        // Write out
        ctx.write(response);

        // Construct a send file thread to write the file to the Chunked buffer
        ChannelFuture sendFileFuture;
        if (ctx.pipeline().get(SslHandler.class) == null) {
            sendFileFuture = ctx.write(new DefaultFileRegion(raf.getChannel(), 0, fileLength),
                    ctx.newProgressivePromise());
        } else {
            sendFileFuture = ctx.write(new HttpChunkedInput(new ChunkedFile(raf, 0, fileLength, 8192)),
                    ctx.newProgressivePromise());
        }

        // Add transmission listener
        sendFileFuture.addListener(new ChannelProgressiveFutureListener() {

            @Override
            public void operationProgressed(ChannelProgressiveFuture future, long progress, long total) {
                if (total < 0) { // total unknown
                    System.err.printf("%s Transfer progress: %s\n", future.channel(), progress);
                } else {
                    System.err.printf("%s Transfer progress: %s/%s\n", future.channel(), progress, total);
                }
            }

            @Override
            public void operationComplete(ChannelProgressiveFuture future) {
                System.err.printf("%s Transfer complete.\n", future.channel());
            }
        });

        // If you use Chunked encoding, you need to send an empty message body at the end of the encoding to mark, indicating that all message bodies have been successfully sent
        ChannelFuture lastContentFuture = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);

        // If the current connection request is not keep-alive, the server actively closes the connection after the last packet is sent.
        if (!HttpUtil.isKeepAlive(request)) {
            // Close the connection when the whole content is written out.
            lastContentFuture.addListener(ChannelFutureListener.CLOSE);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        if (ctx.channel().isActive()) {
            sendError(ctx, INTERNAL_SERVER_ERROR);
        }
    }

    private void setDateAndCacheHeaders(HttpResponse response, File fileToCache) {
        SimpleDateFormat dateFormatter = new SimpleDateFormat(HTTP_DATE_FORMAT, Locale.US);
        dateFormatter.setTimeZone(TimeZone.getTimeZone(HTTP_DATE_GMT_TIMEZONE));

        // Date header
        Calendar time = new GregorianCalendar();
        response.headers().set(DATE, dateFormatter.format(time.getTime()));

        // Add cache headers
        time.add(Calendar.SECOND, HTTP_CACHE_SECONDS);
        response.headers().set(EXPIRES, dateFormatter.format(time.getTime()));
        response.headers().set(CACHE_CONTROL, "private, max-age=" + HTTP_CACHE_SECONDS);
        response.headers().set(LAST_MODIFIED, dateFormatter.format(new Date(fileToCache.lastModified())));
    }

    private void sendNotModified(ChannelHandlerContext ctx) {
        FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, NOT_MODIFIED);
        setDateHeader(response);

        // Close the connection as soon as the error message is sent.
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    // Redirect operation
    private static void sendRedirect(ChannelHandlerContext ctx, String newUri) {
        // Create a response object
        FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, FOUND);
        //Set the new request address into the response object
        response.headers().set(LOCATION, newUri);
        // Use a ctx object to write out and refresh into a SocketChannel, and actively close the connection (here refers to the connection processing the thread that sends data)
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    // The web server can jump to a Controller, traverse the file and jump to
    private void sendListing(ChannelHandlerContext ctx, File dir) {
        FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, OK);
        response.headers().set(CONTENT_TYPE, "text/html; charset=UTF-8");

        StringBuilder buf = new StringBuilder();
        String dirPath = dir.getPath();

        buf.append("<!DOCTYPE html>\r\n");
        buf.append("<html><head><title>");
        buf.append("Listing of: ");
        buf.append(dirPath);
        buf.append("</title></head><body>\r\n");

        buf.append("<h3>Listing of: ");
        buf.append(dirPath);
        buf.append("</h3>\r\n");

        buf.append("<ul>");
        buf.append("<li><a href=\"../\">..</a></li>\r\n");
        File[] files = dir.listFiles();
        if (null != files && files.length > 0) {
            for (File f : files) {
                if (f.isHidden() || !f.canRead()) {
                    continue;
                }

                String name = f.getName();
                if (!ALLOWED_FILE_NAME.matcher(name).matches()) {
                    continue;
                }

                buf.append("<li><a href=\"");
                buf.append(name);
                buf.append("\">");
                buf.append(name);
                buf.append("</a></li>\r\n");
            }
        }

        buf.append("</ul></body></html>\r\n");
        ByteBuf buffer = Unpooled.copiedBuffer(buf, CharsetUtil.UTF_8);
        response.content().writeBytes(buffer);
        buffer.release();

        // Close the connection as soon as the error message is sent.
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private String sanitizeUri(String uri) {
        try {

            // Use UTF-8 character set
            uri = URLDecoder.decode(uri, CharsetUtil.UTF_8.displayName());
        } catch (UnsupportedEncodingException e) {
            try {
                uri = URLDecoder.decode(uri, CharsetUtil.ISO_8859_1.displayName());
            } catch (UnsupportedEncodingException e1) {
                //Throw expected exception
                throw new Error();
            }
        }
        // Fine-grained judgment of uri: 4-step verification operation
        // 1. Basic verification
        if (!uri.startsWith(url)) {
            return null;
        }
        // 2.
        if (!uri.startsWith("/")) {
            return null;
        }
        // 3. Replace file separator with file path separator for local operating system
        uri = uri.replace('/', File.separatorChar);
        // 4. Second verification of legitimacy
        if (uri.contains(File.separator + ".") || uri.contains("." + File.separator) || uri.startsWith(".")
                || uri.endsWith(".") || INSECURE_URI.matcher(uri).matches()) {
            return null;
        }
        // The current project directory + URI constructs the absolute path to return
        return System.getProperty("user.dir") + File.separator + uri;
    }

    private static void setContentTypeHeader(HttpResponse response, File file) {
        // Get file type using mime object
        MimetypesFileTypeMap map = new MimetypesFileTypeMap();
        response.headers().set(CONTENT_TYPE, map.getContentType(file.getPath()));
    }

    // Error message
    private void sendError(ChannelHandlerContext ctx, HttpResponseStatus status) {
        ByteBuf byteBuf = Unpooled.copiedBuffer("Failure :" + status.toString() + "\r\n", CharsetUtil.UTF_8);
        // Create a response object
        FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, status, byteBuf);
        // Set response headers
        response.headers().set(CONTENT_TYPE, "text/plain;charset=UTF-8");
        // Use a ctx object to write out and refresh into a SocketChannel, and actively close the connection (here refers to the connection processing the thread that sends data)
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private static void setDateHeader(FullHttpResponse response) {
        SimpleDateFormat dateFormatter = new SimpleDateFormat(HTTP_DATE_FORMAT, Locale.US);
        dateFormatter.setTimeZone(TimeZone.getTimeZone(HTTP_DATE_GMT_TIMEZONE));

        Calendar time = new GregorianCalendar();
        response.headers().set(DATE, dateFormatter.format(time.getTime()));
    }
}