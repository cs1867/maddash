package net.es.maddash.www.rest;

import javax.json.JsonObject;
import javax.ws.rs.DELETE;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import javax.ws.rs.core.Response.Status;

import net.es.maddash.MaDDashGlobals;
import net.es.maddash.NetLogger;

import org.apache.log4j.Logger;

@Path("/maddash/admin/events/{eventId}")
public class AdminEventResource {
    Logger log = Logger.getLogger(AdminEventResource.class);
    Logger netLogger = Logger.getLogger("netLogger");
    @Context UriInfo uriInfo;
    
    final private String DELETE_EVENT = "maddash.www.rest.AdminEventResource.delete";
    
    @Produces("application/json")
    @DELETE
    public Response delete(@PathParam("eventId") int eventId, @Context HttpHeaders httpHeaders){
        NetLogger netLog = NetLogger.getTlogger();
        this.netLogger.info(netLog.start(DELETE_EVENT));
        
        JsonObject json = null;
        try{
            json = MaDDashGlobals.getInstance().getResourceManager().deleteEvent(eventId);
        }catch(Exception e){
            this.netLogger.error(netLog.error(DELETE_EVENT, e.getMessage()));
            return Response.serverError().entity(e.getMessage()).build();
        }
        //detect if not found
        if(json == null){
            this.netLogger.error(netLog.error(DELETE_EVENT, "Event resource not found"));
            return Response.status(Status.NOT_FOUND).entity("Event resource not found").build();
        }
        
        this.netLogger.info(netLog.end(DELETE_EVENT));
        return Response.ok().entity(json.toString()).build();
    }

}
