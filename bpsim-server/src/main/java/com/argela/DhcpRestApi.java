package com.argela;

import io.quarkus.grpc.GrpcService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

@Path("/dhcp")
public class DhcpRestApi {
    @ConfigProperty(name = "uniPort")
    int uniPort;

    @ConfigProperty(name = "dhcp.pon.port.start", defaultValue = "0")
    int ponPortStart;

    @ConfigProperty(name = "dhcp.pon.port.count", defaultValue = "16")
    int ponPortCount;

    @ConfigProperty(name = "dhcp.onu.port.start", defaultValue = "0")
    int onuPortStart;

    @ConfigProperty(name = "dhcp.onu.port.count", defaultValue = "128")
    int onuPortCount;

    @Inject
    @GrpcService
    DhcpGrpcServer grpcServer;

    @Inject
    DeviceService deviceService;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response simulateDhcpRequest(DhcpSimulationRequest request) {
        if (request == null) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }

        // PON port kontrolü
        int ponPortMax = ponPortStart + ponPortCount - 1;
        if (request.getPonPort() < ponPortStart || request.getPonPort() > ponPortMax) {
            return Response
                    .status(Response.Status.BAD_REQUEST)
                    .entity("PON Port must be between " + ponPortStart + " and " + ponPortMax +
                            " (configured range), got: " + request.getPonPort())
                    .build();
        }

        // ONU port kontrolü
        int onuPortMax = onuPortStart + onuPortCount - 1;
        if (request.getOnuId() < onuPortStart || request.getOnuId() > onuPortMax) {
            return Response
                    .status(Response.Status.BAD_REQUEST)
                    .entity("ONU ID must be between " + onuPortStart + " and " + onuPortMax +
                            " (configured range), got: " + request.getOnuId())
                    .build();
        }

        // UNI kontrolü (her zaman 0 olmalı storm'da, ama manual request'lerde esnek olabilir)
        if (request.getUniId() < 0 || request.getUniId() > uniPort - 1) {
            return Response
                    .status(Response.Status.BAD_REQUEST)
                    .entity("UNI ID must be between 0 and " + (uniPort - 1) + ", got: " + request.getUniId())
                    .build();
        }

        System.out.printf("Received DHCP Request: packetType=%s, ponPort=%d, onuId=%d, uniId=%d, gemPort=%d, cTag=%d%n",
                request.getPacketType(), request.getPonPort(), request.getOnuId(), request.getUniId(),
                request.getGemPort(), request.getCTag());

        grpcServer.sendDhcp(request);

        return Response.ok().entity("{\"status\": \"streamed to gRPC\"}").build();
    }

    @GET
    @Path("/list")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listDhcpSessions(@QueryParam("vlanId") Integer vlanId,
                                     @QueryParam("ponPort") Integer ponPort,
                                     @QueryParam("onuId") Integer onuId,
                                     @QueryParam("uniId") Integer uniId,
                                     @QueryParam("gemPort") Integer gemPort,
                                     @QueryParam("state") String state,
                                     @QueryParam("filter") String filter) {
        try {
            Collection<DeviceInfo> allDevices = grpcServer.deviceService.getAllDevices();
            Stream<DeviceInfo> deviceStream = allDevices.stream();

            // Apply specific filters
            if (vlanId != null) {
                deviceStream = deviceStream.filter(device -> device.getVlanId() == vlanId);
            }

            if (ponPort != null) {
                deviceStream = deviceStream.filter(device -> device.getPonPort() == ponPort);
            }

            if (onuId != null) {
                deviceStream = deviceStream.filter(device -> device.getOnuId() == onuId);
            }

            if (uniId != null) {
                deviceStream = deviceStream.filter(device -> device.getUniId() == uniId);
            }

            if (gemPort != null) {
                deviceStream = deviceStream.filter(device -> device.getGemPort() == gemPort);
            }

            if (state != null && !state.trim().isEmpty()) {
                String normalizedState = state.trim().toUpperCase();
                deviceStream = deviceStream.filter(device ->
                        device.getState() != null && device.getState().toUpperCase().contains(normalizedState));
            }

            // Apply general text filter (searches in multiple fields)
            if (filter != null && !filter.trim().isEmpty()) {
                String normalizedFilter = filter.trim().toLowerCase();
                deviceStream = deviceStream.filter(device ->
                        matchesGeneralFilter(device, normalizedFilter));
            }

            List<DeviceInfo> filteredDevices = deviceStream.toList();

            return Response.ok(filteredDevices).build();

        } catch (Exception e) {
            System.err.println("Error retrieving DHCP sessions: " + e.getMessage());
            e.printStackTrace();
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\": \"Failed to retrieve DHCP sessions\", \"message\": \"" + e.getMessage() + "\"}")
                    .build();
        }
    }

    /**
     * General filter that searches across multiple fields
     */
    private boolean matchesGeneralFilter(DeviceInfo device, String filter) {
        return (device.getClientMac() != null && device.getClientMac().toLowerCase().contains(filter)) ||
                (device.getIpAddress() != null && device.getIpAddress().toLowerCase().contains(filter)) ||
                (device.getState() != null && device.getState().toLowerCase().contains(filter)) ||
                (device.getRequiredIp() != null && device.getRequiredIp().toLowerCase().contains(filter)) ||
                String.valueOf(device.getId()).contains(filter) ||
                String.valueOf(device.getVlanId()).contains(filter) ||
                String.valueOf(device.getPonPort()).contains(filter) ||
                String.valueOf(device.getOnuId()).contains(filter) ||
                String.valueOf(device.getUniId()).contains(filter) ||
                String.valueOf(device.getGemPort()).contains(filter);
    }

    @POST
    @Path("/storm")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response simulateDhcpStorm(DhcpStormRequest request) {
        if (request == null || !request.isValid()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\": \"Either rate or intervalSec must be provided and greater than zero\"}")
                    .build();
        }

        try {
            // Storm durumunu kontrol et
            if (grpcServer.isStormInProgress()) {
                return Response.status(Response.Status.CONFLICT)
                        .entity("{\"error\": \"DHCP storm is already in progress. Please wait for completion or cancel the current storm.\"}")
                        .build();
            }

            grpcServer.simulateDhcpStorm(request.getRate(), request.getIntervalSec());

            return Response.ok()
                    .entity("{\"status\": \"DHCP storm started successfully\"}")
                    .build();

        } catch (RuntimeException e) {
            return Response.status(Response.Status.CONFLICT)
                    .entity("{\"error\": \"" + e.getMessage() + "\"}")
                    .build();
        } catch (Exception e) {
            System.err.println("Error starting DHCP storm: " + e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\": \"Failed to start DHCP storm\", \"message\": \"" + e.getMessage() + "\"}")
                    .build();
        }
    }

    /**
     * Tüm cihazları ve IP pool'larını temizler
     */
    @DELETE
    @Path("/clear-all")
    @Produces(MediaType.APPLICATION_JSON)
    public Response clearAllDevices() {
        try {
            int deviceCount = deviceService.getAllDevices().size();

            // Tüm cihazları temizle (IP'ler ve MAC'ler otomatik serbest bırakılacak)
            deviceService.clearAll();

            System.out.println("All devices cleared. Total cleared: " + deviceCount);

            return Response.ok()
                    .entity("{\"status\": \"success\", \"message\": \"All devices cleared\", \"clearedCount\": " + deviceCount + "}")
                    .build();

        } catch (Exception e) {
            System.err.println("Error clearing all devices: " + e.getMessage());
            e.printStackTrace();
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\": \"Failed to clear devices\", \"message\": \"" + e.getMessage() + "\"}")
                    .build();
        }
    }

    @POST
    @Path("/storm/cancel")
    @Produces(MediaType.APPLICATION_JSON)
    public Response cancelStorm() {
        try {
            grpcServer.cancelStorm();
            return Response.ok()
                    .entity("{\"status\": \"Storm cancelled successfully\"}")
                    .build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\": \"Failed to cancel storm\", \"message\": \"" + e.getMessage() + "\"}")
                    .build();
        }
    }
}
