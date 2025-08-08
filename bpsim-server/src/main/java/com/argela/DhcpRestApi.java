package com.argela;

import io.quarkus.grpc.GrpcService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Collection;

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
    public Response listDhcpSessions(@QueryParam("filter") String filter) {
        try {
            Collection<DeviceInfo> bb = grpcServer.deviceService.getAllDevices();
            return Response.ok(bb).build();

        } catch (Exception e) {
            System.err.println("Error retrieving DHCP sessions: " + e.getMessage());
            e.printStackTrace();

            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\": \"Failed to retrieve DHCP sessions\", \"message\": \"" + e.getMessage() + "\"}")
                    .build();
        }
    }
}
