package com.argela;

import io.quarkus.grpc.GrpcService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("/dhcp")
public class DhcpRestApi {
    private static final Logger logger = LoggerFactory.getLogger(DhcpRestApi.class);

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

    /**
     * Simulates a DHCP request based on the provided parameters
     * @param request The DHCP simulation request containing packet type and network parameters
     * @return Response indicating success or error status
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response simulateDhcpRequest(DhcpSimulationRequest request) {
        if (request == null) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }

        // PON port validation
        int ponPortMax = ponPortStart + ponPortCount - 1;
        if (request.getPonPort() < ponPortStart || request.getPonPort() > ponPortMax) {
            return Response
                    .status(Response.Status.BAD_REQUEST)
                    .entity("PON Port must be between " + ponPortStart + " and " + ponPortMax +
                            " (configured range), got: " + request.getPonPort())
                    .build();
        }

        // ONU port validation
        int onuPortMax = onuPortStart + onuPortCount - 1;
        if (request.getOnuId() < onuPortStart || request.getOnuId() > onuPortMax) {
            return Response
                    .status(Response.Status.BAD_REQUEST)
                    .entity("ONU ID must be between " + onuPortStart + " and " + onuPortMax +
                            " (configured range), got: " + request.getOnuId())
                    .build();
        }

        // UNI validation (should always be 0 in storm, but can be flexible in manual requests)
        if (request.getUniId() < 0 || request.getUniId() > uniPort - 1) {
            return Response
                    .status(Response.Status.BAD_REQUEST)
                    .entity("UNI ID must be between 0 and " + (uniPort - 1) + ", got: " + request.getUniId())
                    .build();
        }

        logger.info("Received DHCP Request: packetType={}, ponPort={}, onuId={}, uniId={}, gemPort={}, cTag={}",
                request.getPacketType(), request.getPonPort(), request.getOnuId(), request.getUniId(),
                request.getGemPort(), request.getCTag());

        grpcServer.sendDhcp(request);

        return Response.ok().entity("{\"status\": \"streamed to gRPC\"}").build();
    }

    /**
     * Lists DHCP sessions with optional filtering
     * @param vlanId Filter by VLAN ID (optional)
     * @param ponPort Filter by PON port (optional)
     * @param onuId Filter by ONU ID (optional)
     * @param uniId Filter by UNI ID (optional)
     * @param gemPort Filter by GEM port (optional)
     * @param state Filter by device state (optional)
     * @param filter General text filter for multiple fields (optional)
     * @return Response containing filtered list of DHCP sessions
     */
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
            logger.error("Error retrieving DHCP sessions: {}", e.getMessage(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\": \"Failed to retrieve DHCP sessions\", \"message\": \"" + e.getMessage() + "\"}")
                    .build();
        }
    }

    /**
     * General filter that searches across multiple fields
     * @param device The device to check against the filter
     * @param filter The filter string to search for
     * @return true if device matches the filter, false otherwise
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

    /**
     * Simulates a DHCP storm by creating multiple DHCP requests rapidly
     * @param request The storm configuration containing rate or interval parameters
     * @return Response indicating success or error status
     */
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
            // Check storm status
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
            logger.error("Error starting DHCP storm: {}", e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\": \"Failed to start DHCP storm\", \"message\": \"" + e.getMessage() + "\"}")
                    .build();
        }
    }

    /**
     * Clears all devices and IP pools from the system
     * @return Response indicating success or error status with count of cleared devices
     */
    @DELETE
    @Path("/clear-all")
    @Produces(MediaType.APPLICATION_JSON)
    public Response clearAllDevices() {
        try {
            int deviceCount = deviceService.getAllDevices().size();

            // Clear all devices (IPs and MACs will be automatically released)
            deviceService.clearAll();

            logger.info("All devices cleared. Total cleared: {}", deviceCount);

            return Response.ok()
                    .entity("{\"status\": \"success\", \"message\": \"All devices cleared\", \"clearedCount\": " + deviceCount + "}")
                    .build();

        } catch (Exception e) {
            logger.error("Error clearing all devices: {}", e.getMessage(), e);

            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\": \"Failed to clear devices\", \"message\": \"" + e.getMessage() + "\"}")
                    .build();
        }
    }

    /**
     * Cancels the currently running DHCP storm
     * @return Response indicating success or error status
     */
    @POST
    @Path("/storm/cancel")
    @Produces(MediaType.APPLICATION_JSON)
    public Response cancelStorm() {
        try {
            if (!grpcServer.isStormInProgress()) {
                return Response.ok()
                        .entity("{\"status\": \"No active storm to cancel\"}")
                        .build();
            }

            grpcServer.cancelStorm();

            return Response.ok()
                    .entity("{\"status\": \"Storm cancellation initiated\"}")
                    .build();

        } catch (Exception e) {
            logger.error("Error cancelling storm: {}", e.getMessage(), e);

            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\": \"Failed to cancel storm\", \"message\": \"" + e.getMessage() + "\"}")
                    .build();
        }
    }

    /**
     * Gets system configuration information
     * @return Response containing PON port count, ONU port count, and total device capacity
     */
    @GET
    @Path("/info")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getSystemInfo() {
        try {
            Map<String, Object> info = new HashMap<>();

            info.put("ponPortCount", ponPortCount);
            info.put("onuPortCount", onuPortCount);
            info.put("totalDeviceCapacity", ponPortCount * onuPortCount);

            return Response.ok(info).build();

        } catch (Exception e) {
            logger.error("Error getting system info: {}", e.getMessage(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\": \"Failed to get system info\", \"message\": \"" + e.getMessage() + "\"}")
                    .build();
        }
    }
}