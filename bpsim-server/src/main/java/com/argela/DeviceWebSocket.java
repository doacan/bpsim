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

        StormStatusMessage stormMessage = new StormStatusMessage("ready", null, null);
        session.getAsyncRemote().sendText(jsonb.toJson(stormMessage));
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

    public static class StormStatusMessage {
        private String type = "storm_status";
        private String status; // ready, progress, error
        private StormParams params;
        private String message;

        public StormStatusMessage(String status, StormParams params, String message) {
            this.status = status;
            this.params = params;
            this.message = message;
        }

        // Getters and setters
        public String getType() { return type; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public StormParams getParams() { return params; }
        public void setParams(StormParams params) { this.params = params; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }

        public static class StormParams {
            private Integer rate;
            private Double intervalSec;

            public StormParams(Integer rate, Double intervalSec) {
                this.rate = rate;
                this.intervalSec = intervalSec;
            }

            public Integer getRate() { return rate; }
            public void setRate(Integer rate) { this.rate = rate; }
            public Double getIntervalSec() { return intervalSec; }
            public void setIntervalSec(Double intervalSec) { this.intervalSec = intervalSec; }
        }
    }

    public static void broadcastStormStatus(String status, Integer rate, Double intervalSec, String message) {
        StormStatusMessage.StormParams params = null;
        if (rate != null || intervalSec != null) {
            params = new StormStatusMessage.StormParams(rate, intervalSec);
        }

        StormStatusMessage stormMessage = new StormStatusMessage(status, params, message);
        String json = jsonb.toJson(stormMessage);

        for (Session session : sessions) {
            session.getAsyncRemote().sendText(json);
        }
    }
}

