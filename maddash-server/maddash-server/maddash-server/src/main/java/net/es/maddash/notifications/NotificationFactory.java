package net.es.maddash.notifications;

import javax.json.JsonObject;

public class NotificationFactory {
    static public String TYPE_EMAIL = "email";
    static public String TYPE_SERVICENOW = "servicenow";
    
    static public Notification create(String name, String type, JsonObject params){
        Notification notifier = null;
        if(TYPE_EMAIL.equals(type)){
            notifier= new EmailNotification();
        }else if(TYPE_SERVICENOW.equals(type)){
            notifier= new ServiceNowNotification();
        }else{
            throw new RuntimeException("Unable to create Notification of type " + type);
        }
        
        notifier.init(name, params);
        return notifier;
        
    }
    
    static public boolean isValidType(String type){
        if(TYPE_EMAIL.equals(type)){
            return true;
        }else if(TYPE_SERVICENOW.equals(type)){
            return true;
        }


        return false;
    }
    
}
