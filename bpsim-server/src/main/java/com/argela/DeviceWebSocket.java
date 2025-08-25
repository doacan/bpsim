package com.argela;

import jakarta.websocket.*;
import jakarta.websocket.server.ServerEndpoint;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket endpoint for real-time device updates and storm status notifications
 */
@ServerEndpoint("/ws/devices")
@ApplicationScoped
public class DeviceWebSocket {

    private static final Set<Session> sessions = ConcurrentHashMap.newKeySet();
    private static final Jsonb jsonb = JsonbBuilder.create();

    @Inject
    DeviceService deviceService;

    /**
     * Handles new WebSocket connection
     * @param session The WebSocket session that opened
     */
    @OnOpen
    public void onOpen(Session session) {
        sessions.add(session);
        // Send all devices when connected
        for (DeviceInfo device : deviceService.getAllDevices()) {
            session.getAsyncRemote().sendText(jsonb.toJson(device));
        }

        StormStatusMessage stormMessage = new StormStatusMessage("ready", null, null);
        session.getAsyncRemote().sendText(jsonb.toJson(stormMessage));
    }

    /**
     * Handles WebSocket connection closure
     * @param session The WebSocket session that closed
     */
    @OnClose
    public void onClose(Session session) {
        sessions.remove(session);
    }

    @OnError
    public void onError(Session session, Throwable throwable) {
        sessions.remove(session);
    }

    /**
     * Broadcasts device information to all connected WebSocket clients
     * @param device The device information to broadcast
     */
    public static void broadcastDevice(DeviceInfo device) {
        String json = jsonb.toJson(device);
        for (Session session : sessions) {
            session.getAsyncRemote().sendText(json);
        }
    }

    /**
     * Message class for storm status updates
     */
    public static class StormStatusMessage {
        private String type = "storm_status";
        private String status; // ready, progress, error
        private StormParams params;
        private String message;

        /**
         * Creates a new storm status message
         * @param status The current storm status (ready, progress, error)
         * @param params The storm parameters (rate or interval)
         * @param message Additional status message
         */
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

        /**
         * Storm parameters class containing rate and interval settings
         */
        public static class StormParams {
            private Integer rate;
            private Double intervalSec;

            /**
             * Creates storm parameters
             * @param rate Number of devices per second (optional)
             * @param intervalSec Interval between devices in seconds (optional)
             */
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

    /**
     * Broadcasts storm status to all connected WebSocket clients
     * @param status The current storm status (ready, progress, error)
     * @param rate Number of devices per second (optional)
     * @param intervalSec Interval between devices in seconds (optional)
     * @param message Additional status message
     */
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