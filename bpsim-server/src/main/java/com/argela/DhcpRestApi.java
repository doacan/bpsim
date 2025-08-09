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
    @Inject
    @ConfigProperty(name = "ponPort")
    int ponPort;

    @Inject
    @ConfigProperty(name = "onuPort")
    int onuPort;

    @Inject
    @ConfigProperty(name = "uniPort")
    int uniPort;

    @Inject
    @GrpcService
    DhcpGrpcServer grpcServer;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response simulateDhcpRequest(DhcpSimulationRequest request) {
        if (request == null) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }

        if (request.getPonPort() > (ponPort - 1) ) {
            return Response
                    .status(Response.Status.BAD_REQUEST)
                    .entity("The number of PON IDs exceeds the allowed maximum of " + ponPort)
                    .build();
        }

        if (request.getOnuId() > (onuPort - 1) ) {
            return Response
                    .status(Response.Status.BAD_REQUEST)
                    .entity("The number of ONU IDs exceeds the allowed maximum of " + onuPort)
                    .build();
        }

        if (request.getUniId() > (uniPort - 1) ) {
            return Response
                    .status(Response.Status.BAD_REQUEST)
                    .entity("The number of UNI IDs exceeds the allowed maximum of " + uniPort)
                    .build();
        }

        System.out.printf("Received DHCP Request: packetType=%s, ponPort=%d, onuId=%d, uniId=%d, gemPort=%d, cTag=%d%n",
                request.getPacketType(), request.getPonPort(), request.getOnuId(), request.getUniId(), request.getGemPort(), request.getCTag());

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

        grpcServer.simulateDhcpStorm(request.getRate(), request.getIntervalSec());

        return Response.ok()
                .entity("{\"status\": \"DHCP storm started\"}")
                .build();
    }
}
