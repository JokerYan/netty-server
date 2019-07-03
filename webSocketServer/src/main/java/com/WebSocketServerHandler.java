package com;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.util.CharsetUtil;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 * @author lilinfeng
 * @version 1.0
 * @date 2014年2月14日
 */
public class WebSocketServerHandler extends SimpleChannelInboundHandler<Object> {
    private static final Logger logger = Logger
            .getLogger(WebSocketServerHandler.class.getName());

    private static ArrayList<ClientInfo> clientGroup = new ArrayList<>();

    private WebSocketServerHandshaker handshaker;

    @Override
    public void channelRead0(ChannelHandlerContext ctx, Object msg)
            throws Exception {
        // 传统的HTTP接入
        if (msg instanceof FullHttpRequest) {
            handleHttpRequest(ctx, (FullHttpRequest) msg);
        }
        // WebSocket接入
        else if (msg instanceof WebSocketFrame) {
            handleWebSocketFrame(ctx, (WebSocketFrame) msg);
        }
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        ctx.flush();
    }

    private void handleHttpRequest(ChannelHandlerContext ctx,
                                   FullHttpRequest req) throws Exception {

        // 如果HTTP解码失败，返回HTTP异常
        if (!req.decoderResult().isSuccess()
                || (!"websocket".equals(req.headers().get("Upgrade")))) {
            sendHttpResponse(ctx, req, new DefaultFullHttpResponse(HTTP_1_1,
                    BAD_REQUEST));
            return;
        }

        // 构造握手响应返回，本机测试
        WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory(
                "ws://localhost:9998/websocket", null, false);
        handshaker = wsFactory.newHandshaker(req);
        if (handshaker == null) {
            WebSocketServerHandshakerFactory
                    .sendUnsupportedVersionResponse(ctx.channel());
        } else {
            handshaker.handshake(ctx.channel(), req);
        }
    }

    private void handleWebSocketFrame(ChannelHandlerContext ctx,
                                      WebSocketFrame frame) {

        // 判断是否是关闭链路的指令
        if (frame instanceof CloseWebSocketFrame) {
            handshaker.close(ctx.channel(),
                    (CloseWebSocketFrame) frame.retain());
            return;
        }
        // 判断是否是Ping消息
        if (frame instanceof PingWebSocketFrame) {
            ctx.channel().write(
                    new PongWebSocketFrame(frame.content().retain()));
            return;
        }

        if(frame instanceof BinaryWebSocketFrame){
            ArrayList<ClientInfo> inactiveClient = new ArrayList<>();
            for(ClientInfo client : clientGroup){
                if(/*client.context != ctx &&*/ client.isSubscribed(ctx)){
                    try{
//                        System.out.println("Sending audio to: " + client.name);
                        client.context.write(frame.retain());
                    }catch (Exception e){
                        inactiveClient.add(client);
                    }
                }
                if(!client.context.channel().isActive()){
                    inactiveClient.add(client);
                }
            }
            for(ClientInfo client : inactiveClient){
                clientGroup.remove(client);
                System.out.println("Client Removed: " + client.name);
                updateClientChange();
            }
        }

        if(frame instanceof TextWebSocketFrame){
            // 返回应答消息
            String request = ((TextWebSocketFrame) frame).text();
            JsonElement root = new JsonParser().parse(request);
            if(root.getAsJsonObject().get("type").getAsString().equals("register")){
                if(root.getAsJsonObject().get("role").getAsString().equals("Audio Combined")){
                    String name = root.getAsJsonObject().get("name").getAsString();
                    boolean found = false;
                    for(ClientInfo client : clientGroup){
                        if(client.context == ctx){
                            found = true;
                            break;
                        }
                        if(client.name == name){
                            found = true;
                            client.context = ctx;
                            break;
                        }
                    }
                    if(!found){
                        clientGroup.add(new ClientInfo(name, ctx));
                        ctx.write(new TextWebSocketFrame(jsonifyMessage("Context Registered")));
                        updateClientChange();
                    }
                }
                System.out.println("Current client count: " + Integer.toString(clientGroup.size()));
            }else if(root.getAsJsonObject().get("type").getAsString().equals("update subscription")){
                String clientName = root.getAsJsonObject().get("clientName").getAsString();
                JsonArray jsonArray = root.getAsJsonObject().get("targetNames").getAsJsonArray();
                ArrayList<String> targetNames = new ArrayList<>();
                for(JsonElement element : jsonArray){
                    targetNames.add(element.getAsString());
                }
                updateSubscription(clientName, targetNames);
            }
//            System.out.println("Message Received: " + request);
            if (logger.isLoggable(Level.FINE)) {
                logger.fine(String.format("%s received %s", ctx.channel(), request));
            }
        }
//        ctx.channel().write(
//                new TextWebSocketFrame("Message echo from server: " + request));
    }

    private static void sendHttpResponse(ChannelHandlerContext ctx,
                                         FullHttpRequest req, FullHttpResponse res) {
        // 返回应答给客户端
        if (res.status().code() != 200) {
            ByteBuf buf = Unpooled.copiedBuffer(res.status().toString(),
                    CharsetUtil.UTF_8);
            res.content().writeBytes(buf);
            buf.release();
            HttpUtil.setContentLength(res, res.content().readableBytes());
        }

        // 如果是非Keep-Alive，关闭连接
        ChannelFuture f = ctx.channel().writeAndFlush(res);
        if (!HttpUtil.isKeepAlive(req) || res.status().code() != 200) {
            f.addListener(ChannelFutureListener.CLOSE);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)
            throws Exception {
        cause.printStackTrace();
        ctx.close();
    }

    private class ClientInfo{
        public String name;
        public ChannelHandlerContext context;
        public ArrayList<String> subscription;

        public ClientInfo(String name, ChannelHandlerContext context) {
            this.name = name;
            this.context = context;
            this.subscription = new ArrayList<>();
        }

        public boolean isSubscribed(ChannelHandlerContext context){
            for(ClientInfo client : clientGroup){
                if(client.context == context){
                    return subscription.contains(client.name);
                }
            }
            return false;
        }
    }

    private void updateClientChange(){
        Gson gson = new Gson();
        ArrayList<String> clientNames = new ArrayList<>();
        for(ClientInfo client : clientGroup){
            clientNames.add(client.name);
        }
        String clientNameString = gson.toJson(clientNames);
        Map<String, String> jsonMap = new HashMap<>();
        jsonMap.put("type", "update client changes");
        jsonMap.put("names", clientNameString);
        String jsonString = gson.toJson(jsonMap);
        for(ClientInfo client : clientGroup){
            client.context.write(new TextWebSocketFrame(jsonString));
        }
    }

    private String jsonifyMessage(String message){
        Gson gson = new Gson();
        Map<String, String> jsonMap = new HashMap<>();
        jsonMap.put("type", "message");
        jsonMap.put("message", message);
        String jsonString = gson.toJson((jsonMap));
        return jsonString;
    }

    private void updateSubscription(String clientName, ArrayList<String> targetNames){
        for(ClientInfo client : clientGroup){
            if(client.name.equals(clientName)){
                client.subscription = targetNames;
                System.out.print("Subscription updated: ");
                System.out.println(targetNames  );
                break;
            }
        }
    }
}