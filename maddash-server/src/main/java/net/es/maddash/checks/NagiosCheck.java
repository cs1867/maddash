package net.es.maddash.checks;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

import net.es.maddash.NetLogger;

import org.apache.log4j.Logger;

/**
 * Check implementation that runs a nagios command.It has one check specific parameter
 * named <i>command</i>. This is the nagios command to execute on the system. It loads the message
 * and statistics returned by the Nagios check in the result 
 * 
 * @author Andy Lake <andy@es.net>
 *
 */
public class NagiosCheck implements Check{
    private Logger log = Logger.getLogger(NagiosCheck.class);
    private Logger netlogger = Logger.getLogger("netlogger");
    
    final static public String PARAM_COMMAND = "command";
    final protected String PROP_DEFAULT = "default";

    public CheckResult check(String gridName, String rowName, String colName,
            Map params, TemplateVariableMap rowVars, TemplateVariableMap colVars, int timeout) {
        if(!params.containsKey(PARAM_COMMAND) || params.get(PARAM_COMMAND) == null){
            return new CheckResult(CheckConstants.RESULT_UNKNOWN, 
                    "Command not defined. Please check config file", null);
        }
        String command = this.parseStringorMap(params, PARAM_COMMAND, rowName, colName, PROP_DEFAULT);
        //if still not set then throw error
        if(command == null){
            return new CheckResult(CheckConstants.RESULT_UNKNOWN,
                    "Default command not defined. Please check config file", null);
        }
        
        for(String rowVar : rowVars.keySet()){
        	command = command.replaceAll("%row." + rowVar, rowVars.get(rowVar));
        }
        command = command.replaceAll("%row", rowName);
        for(String colVar : colVars.keySet()){
        	command = command.replaceAll("%col." + colVar, rowVars.get(colVar));
        }
        command = command.replaceAll("%col", colName);
        
        NetLogger netLog = NetLogger.getTlogger();
        CheckResult result = null;
        Runtime runtime = Runtime.getRuntime();
        Process process = null;
        try {
            netlogger.debug(netLog.start("maddash.NagiosCheck.runCommand"));
            log.debug("Executing command " + command);
            process = runtime.exec(command);
            WatchDog watchdog = new WatchDog(process);
            watchdog.start();
            watchdog.join(timeout*1000);
            if(watchdog.exit == null){
                result = new CheckResult(CheckConstants.RESULT_UNKNOWN, 
                        "Command timed-out after " + timeout + " seconds",
                        null);
            }else{
                int resultCode = process.exitValue();
                String outputLine = null;
                Map returnParams = null;
                if(resultCode < CheckConstants.RESULT_SUCCESS){
                    outputLine = "Unknown return status " + resultCode + " from command. Verify that it is a valid Nagios plug-in";
                    resultCode = CheckConstants.RESULT_UNKNOWN;
                }else{
                    BufferedReader stdin = new BufferedReader(new InputStreamReader(process.getInputStream()));
                    outputLine = stdin.readLine();
                    returnParams = this.parseReturnParams(outputLine);
                    outputLine = this.formatOutputLine(outputLine);
                }
                result = new CheckResult(resultCode, outputLine, returnParams);
            }
            netlogger.debug(netLog.end("maddash.NagiosCheck.runCommand"));
        } catch (Exception e) {
            netlogger.debug(netLog.error("maddash.NagiosCheck.runCommand", e.getMessage()));
            log.error("Error running nagios check: " + e.getMessage());
            result = new CheckResult(CheckConstants.RESULT_UNKNOWN, 
                    "Exception executing command: " + e.getMessage(), null);
            e.printStackTrace();
        } finally{
            if(process != null){
                process.destroy();
            }
        }
        
        return result;
    }

    protected String parseStringorMap(Map map, String propName, String rowName, String colName, String defaultPropName){
        String val = null;
        //try to get as string for backward compatibility
        try{
            val = (String)map.get(propName); //try to get as string for backward compatibility
        }catch(Exception e){}
        //try to get as map
        if(val == null){
            Map m = (Map)map.get(propName);
            val= this.parseRowColMapProp(m, rowName, colName, defaultPropName);
        }

        return val;
    }

    protected String parseRowColMapProp(Map map, String rowName, String colName, String defaultPropName){
        String val = null;
        if(map.containsKey(rowName) && map.get(rowName) != null){
            Map<String,String> rowMap = (Map<String,String>) map.get(rowName);
            if(rowMap.containsKey(colName) && rowMap.get(colName) != null){
                val = (String) rowMap.get(colName);
            }else if(rowMap.containsKey(defaultPropName) && rowMap.get(defaultPropName) != null){
                val = (String) rowMap.get(defaultPropName);
            }
        }
        //try to set default
        if(map == null){
            val = (String) map.get(defaultPropName);
        }

        return val;
    }

    private String formatOutputLine(String outputLine) {
        if(outputLine == null){
            return "";
        }
        outputLine = outputLine.replaceAll("^\\w+? \\w+? \\-", "");
        outputLine = outputLine.replaceAll("\\|.+", "");
        outputLine.trim();
            
        return outputLine;
    }

    private Map parseReturnParams(String outputLine) {
        HashMap<String, String> params = new HashMap<String, String>();
        if(outputLine == null){
            return params;
        }
        String[] lineParts = outputLine.split("\\|");
        if(lineParts.length == 1){
            return null;
        }
        
        String statsString = lineParts[lineParts.length - 1];
        String[] kvPairs = statsString.split(";+");
        for(String kvPair : kvPairs){
            kvPair = kvPair.trim();
            String[] kv = kvPair.split("=");
            if(kv.length == 2){
                params.put(kv[0], kv[1]);
            }
        }
        
        return params;
    }

    private class WatchDog extends Thread{
        private Process process;
        private Integer exit = null;
        
        public WatchDog(Process process){
            this.process = process;
        }
        
        public void run(){
            try {
                this.exit = this.process.waitFor();
            } catch (InterruptedException e) {
                return;
            }
        }
    }

}
