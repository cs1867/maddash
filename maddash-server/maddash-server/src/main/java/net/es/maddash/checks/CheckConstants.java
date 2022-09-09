package net.es.maddash.checks;

/**
 * Useful constants for check status codes and other values
 * 
 * @author Andy Lake<andy@es.net>
 *
 */
public class CheckConstants {
    final static public int RESULT_SUCCESS = 0;
    final static public int RESULT_WARN = 1;
    final static public int RESULT_CRITICAL = 2;
    final static public int RESULT_UNKNOWN = 3;
    final static public int RESULT_NOTRUN = 4;
    final static public int RESULT_MAINTENANCE = 5;
    final static public String EMPTY_PARAMS = "EMPTY";
    final static public String[] RESULT_SHORT_NAMES = {"OK", "WARNING", "CRITICAL", "UNKNOWN", "NOT RUN", "MAINTENANCE"};
    
}
