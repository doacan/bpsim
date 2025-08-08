package com.argela;

import jakarta.websocket.*;
import jakarta.websocket.server.ServerEndpoint;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@ServerEndpoint("/ws/devices")
@ApplicationScoped
public class DeviceWebSocket {

    private static final Set<Session> sessions = ConcurrentHashMap.newKeySet();
    private static final Jsonb jsonb = JsonbBuilder.create();

    @Inject
    DeviceService deviceService;

    @OnOpen
    public void onOpen(Session session) {
        sessions.add(session);
        // Bağlanınca tüm cihazları gönder
        for (DeviceInfo device : deviceService.getAllDevices()) {
            session.getAsyncRemote().sendText(jsonb.toJson(device));
        }
    }

    @OnClose
    public void onClose(Session session) {
        sessions.remove(session);
    }

    public static void broadcastDevice(DeviceInfo device) {
        String json = jsonb.toJson(device);
        for (Session session : sessions) {
            session.getAsyncRemote().sendText(json);
        }
    }
}

