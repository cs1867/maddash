package net.es.maddash.jobs;

import java.io.StringReader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;

import org.apache.log4j.Logger;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

import net.es.maddash.DBMesh;
import net.es.maddash.MaDDashGlobals;
import net.es.maddash.NetLogger;
import net.es.maddash.madalert.Madalert;
import net.es.maddash.madalert.Mesh;
import net.es.maddash.madalert.Problem;
import net.es.maddash.madalert.Report;
import net.es.maddash.notifications.Notification;
import net.es.maddash.notifications.NotificationFactory;
import net.es.maddash.notifications.NotifyProblem;
import net.es.maddash.notifications.NotifyProblemComparator;

public class NotifyJob implements Job{
    private Logger log = Logger.getLogger(NotifyJob.class);
    private Logger netlogger = Logger.getLogger("netlogger");
    
    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        NetLogger netLog = NetLogger.getTlogger();
        netlogger.info(netLog.start("maddash.NotifyJob.execute"));
        
        //load data map
        JobDataMap dataMap = context.getJobDetail().getJobDataMap();
        int notificationId = dataMap.getInt("notificationId");
        Notification notifier = (Notification) dataMap.get("notifier");;
        int minSeverity = dataMap.getInt("minSeverity");
        int frequency = dataMap.getInt("frequency");
        int resolveAfter = dataMap.getInt("resolveAfter");
        HashMap<String, Boolean> dashboardFilters = (HashMap<String, Boolean>)dataMap.get("dashboardFilters");
        HashMap<String, Boolean> gridFilters = (HashMap<String, Boolean>)dataMap.get("gridFilters");
        HashMap<String, Boolean> siteFilters = (HashMap<String, Boolean>)dataMap.get("siteFilters");
        HashMap<String, Boolean> categoryFilters = (HashMap<String, Boolean>)dataMap.get("categoryFilters");
        
        //query database
        Connection conn = null;
        try{
            //init db and find notification row
            MaDDashGlobals globals = MaDDashGlobals.getInstance();
            conn = globals.getDataSource().getConnection();
            PreparedStatement selStmt = conn.prepareStatement("SELECT id FROM notifications WHERE id=?");
            selStmt.setInt(1, notificationId);
            ResultSet notificationResult = selStmt.executeQuery();
            if(!notificationResult.next()){
                throw new RuntimeException("Unable to find notification with ID " + notificationId + " in database");
            }
            
            //find reports we care about
            List<NotifyProblem> problems = new ArrayList<NotifyProblem>();
            List<NotifyProblem> newProblems = new ArrayList<NotifyProblem>();
            HashMap<String, Boolean> gridMap = new HashMap<String, Boolean>();
            //apply dashboard filters
            if(!dashboardFilters.isEmpty()){
                JsonArray dashboards = MaDDashGlobals.getInstance().getDashboards();
                for(int i = 0; i < dashboards.size(); i++){
                    String dashName = dashboards.getJsonObject(i).getString("name");
                    if(dashboardFilters.containsKey(dashName) && dashboardFilters.get(dashName)){
                        JsonArray dashGrids = dashboards.getJsonObject(i).getJsonArray("grids");
                        for(int j = 0; j < dashGrids.size(); j++){
                            String gridName = dashGrids.getJsonObject(j).getString("name").trim();
                            if(gridFilters.isEmpty()){
                                //if no grid filters, assume all should be added
                                gridMap.put(gridName, true);
                            }else if(gridFilters.containsKey(gridName) && gridFilters.get(gridName)){
                                //otherwise, only add if in grid filter list 
                                gridMap.put(gridName, true);
                            }
                        }
                    }
                }
            }else{
                //apply grid filters
                ResultSet gridSel = conn.createStatement().executeQuery("SELECT DISTINCT gridName FROM grids");
                while(gridSel.next()){
                    if(gridFilters.isEmpty()){
                        //if no grid filters, assume all should be added
                        gridMap.put(gridSel.getString(1), true);
                    }else if(gridFilters.containsKey(gridSel.getString(1)) && gridFilters.get(gridSel.getString(1))){
                        //otherwise, only add if in grid filter list 
                        gridMap.put(gridSel.getString(1), true);
                    }
                }
            }
            //generate reports
            for(String gridName : gridMap.keySet()){
                Mesh mesh = new DBMesh(gridName, "");
                Report report = Madalert.lookupRule(mesh.getName()).createReport(mesh);
                //check global problem. if site filters, ignore global
                if(siteFilters.isEmpty()){
                    if(report.getGlobalMaxSeverity() >= minSeverity){
                        if(report.getGlobalProblems() != null){
                            for(Problem p: report.getGlobalProblems()){
                                if(!categoryFilters.isEmpty() && (!categoryFilters.containsKey(p.getCategory()) || categoryFilters.get(p.getCategory()) == null)){
                                    continue;
                                }
                                if(p.getSeverity() >= minSeverity){
                                    problems.add(new NotifyProblem(gridName, p));
                                }
                            }
                        }
                    }
                }
                
                //check site problems
                for(String site : report.getSites()){
                    //skip if site filters defined and this is not our site
                    if(!siteFilters.isEmpty() && !siteFilters.containsKey(site) && !siteFilters.get(site)){
                        continue;
                    }
                    if(report.getSiteProblems(site) != null){
                        for(Problem p: report.getSiteProblems(site)){
                            if(!categoryFilters.isEmpty() && (!categoryFilters.containsKey(p.getCategory()) || categoryFilters.get(p.getCategory()) == null)){
                                continue;
                            }
                            if(p.getSeverity() >= minSeverity){
                                problems.add(new NotifyProblem(gridName, site, p));
                            }
                        }
                    }
                }
            }
            
            //update db and determine if needs to be sent
            long now = System.currentTimeMillis()/1000;
            long expires = now + frequency;
            long resolved = now - resolveAfter;
            PreparedStatement probSel = conn.prepareStatement("SELECT id, expires FROM notificationProblems WHERE notificationId=? AND checksum=?");
            PreparedStatement probUpdateExpired = conn.prepareStatement("UPDATE notificationProblems SET expires = ?, lastSeen =? WHERE notificationId=? AND checksum=?");
            PreparedStatement probUpdateLastSeen = conn.prepareStatement("UPDATE notificationProblems SET lastSeen =? WHERE notificationId=? AND checksum=?");
            PreparedStatement probSelResolved = conn.prepareStatement("SELECT id, appData FROM notificationProblems WHERE notificationId=? AND lastSeen <= ?");
            PreparedStatement probDelResolved = conn.prepareStatement("DELETE FROM notificationProblems WHERE notificationId=? AND lastSeen <= ?");
            PreparedStatement probDelete = conn.prepareStatement("DELETE FROM notificationProblems WHERE notificationId=? AND expires <= ?");
            PreparedStatement probInsert = conn.prepareStatement("INSERT INTO notificationProblems VALUES(DEFAULT, ?, ?, ?, ?, NULL)");


            // Uncomment for useful debugging output on database tables
            /* System.out.println("Notification ID is " + notificationId);
            System.out.println("-------------------------------------");
            PreparedStatement probAll = conn.prepareStatement("SELECT id, notificationId, checksum, expires, lastSeen, appData FROM notificationProblems WHERE notificationId=?");
            probAll.setInt(1,notificationId);
            ResultSet probAllResult = probAll.executeQuery();
            while(probAllResult.next()){
                System.out.print(probAllResult.getInt(1) + " | ");
                System.out.print(probAllResult.getInt(2) + " | ");
                System.out.print(probAllResult.getString(3) + " | ");
                System.out.print(probAllResult.getLong(4) + " | ");
                System.out.print(probAllResult.getLong(5) + " | ");
                System.out.print(probAllResult.getString(6) + "\n");
            } */


            //now check if a problem has been reported on recently
            for(NotifyProblem p : problems){
                probSel.setInt(1, notificationId);
                probSel.setString(2, p.checksum());
                ResultSet probResult = probSel.executeQuery();
                //Check database to see if problem exists
                if(!probResult.next()){
                    //create a new problem
                    if(frequency > 0){
                        probInsert.setInt(1, notificationId);
                        probInsert.setString(2, p.checksum());
                        probInsert.setLong(3, expires);
                        probInsert.setLong(4, now);
                        probInsert.executeUpdate();
                    }
                    newProblems.add(p);
                }else if(probResult.getLong(2) <= now){
                    //need a new notification
                    probUpdateExpired.setLong(1, expires);
                    probUpdateExpired.setLong(2, now);
                    probUpdateExpired.setInt(3, notificationId);
                    probUpdateExpired.setString(4, p.checksum());
                    probUpdateExpired.executeUpdate();
                    newProblems.add(p);
                }else{
                    //no notification needed, but update last seen
                    probUpdateLastSeen.setLong(1, now);
                    probUpdateLastSeen.setInt(2, notificationId);
                    probUpdateLastSeen.setString(3, p.checksum());
                    probUpdateLastSeen.executeUpdate();

                }
            }

            //make sure we propagate changes
            conn.commit();

            //check if we care about resolved
            ArrayList<String> resolvedAppData = new ArrayList<String>();
            if(resolveAfter > 0) {
                //if we care, grab everything for this notification that looks resolved
                probSelResolved.setInt(1, notificationId);
                probSelResolved.setLong(2, resolved);
                ResultSet probResolvedResult = probSelResolved.executeQuery();
                while(probResolvedResult.next()){
                    resolvedAppData.add(probResolvedResult.getString(2));
                }
                //now delete these. this means we only have on shot to resolve
                probDelResolved.setInt(1, notificationId);
                probDelResolved.setLong(2, resolved);
                probDelResolved.executeUpdate();
            }else{
                //if we don't care about resolved, just clear out everything that is expired
                probDelete.setInt(1, notificationId);
                probDelete.setLong(2, now);
                probDelete.executeUpdate();
            }


            conn.close();
            
            //create notifier and send reports
            Collections.sort(newProblems, new NotifyProblemComparator());
            notifier.send(notificationId, newProblems, resolvedAppData);
            netlogger.info(netLog.end("maddash.NotifyJob.execute"));
        }catch(Exception e){
            if(conn != null){
                try{
                    conn.close();
                }catch(SQLException e2){}
            }
            netlogger.info(netLog.error("maddash.NotifyJob.execute", e.getMessage()));
            log.error("Error scheduling job " + e.getMessage());
            e.printStackTrace();
        }
    }

}
