package com.github.streamshub.console.api;

import java.util.concurrent.CompletionStage;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import com.github.streamshub.console.api.model.ConfigEntry;
import com.github.streamshub.console.api.security.Authorized;
import com.github.streamshub.console.api.security.ResourcePrivilege;
import com.github.streamshub.console.api.service.BrokerService;
import com.github.streamshub.console.config.security.Privilege;

@Path("/api/kafkas/{clusterId}/nodes")
@Tag(name = "Kafka Cluster Resources")
public class BrokersResource {

    @Inject
    BrokerService brokerService;

    @GET
    @Path("{nodeId}/configs")
    @Produces(MediaType.APPLICATION_JSON)
    @APIResponse(responseCode = "200", ref = "Configurations", content = @Content())
    @APIResponse(responseCode = "404", ref = "NotFound")
    @APIResponse(responseCode = "500", ref = "ServerError")
    @APIResponse(responseCode = "504", ref = "ServerTimeout")
    @Authorized
    @ResourcePrivilege(Privilege.GET)
    public CompletionStage<Response> describeConfigs(
            @Parameter(description = "Cluster identifier")
            @PathParam("clusterId")
            String clusterId,

            @PathParam("nodeId")
            @Parameter(description = "Node identifier")
            String nodeId) {

        return brokerService.describeConfigs(nodeId)
            .thenApply(ConfigEntry.ConfigResponse::new)
            .thenApply(Response::ok)
            .thenApply(Response.ResponseBuilder::build);
    }

}
