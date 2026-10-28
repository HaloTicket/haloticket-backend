package com.haloticket.ticket_service.authz;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "authz.event-scope")
public class EventScopeProperties {

    public static class Group {
        private boolean enabled;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    private boolean enabled;
    private Group catalog = new Group();
    private Group orders = new Group();
    private Group checkin = new Group();
    private Group identity = new Group();
    private String internalToken = "";
    private long cacheSeconds = 30;
    private long staleCheckinSeconds = 300;

    private boolean on(Group group) { return enabled && group.isEnabled(); }

    public boolean catalogOn() { return on(catalog); }
    public boolean ordersOn() { return on(orders); }
    public boolean checkinOn() { return on(checkin); }
    public boolean identityOn() { return on(identity); }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Group getCatalog() { return catalog; }
    public void setCatalog(Group catalog) { this.catalog = catalog; }
    public Group getOrders() { return orders; }
    public void setOrders(Group orders) { this.orders = orders; }
    public Group getCheckin() { return checkin; }
    public void setCheckin(Group checkin) { this.checkin = checkin; }
    public Group getIdentity() { return identity; }
    public void setIdentity(Group identity) { this.identity = identity; }
    public String getInternalToken() { return internalToken; }
    public void setInternalToken(String internalToken) { this.internalToken = internalToken == null ? "" : internalToken; }
    public long getCacheSeconds() { return cacheSeconds; }
    public void setCacheSeconds(long cacheSeconds) { this.cacheSeconds = cacheSeconds; }
    public long getStaleCheckinSeconds() { return staleCheckinSeconds; }
    public void setStaleCheckinSeconds(long staleCheckinSeconds) { this.staleCheckinSeconds = staleCheckinSeconds; }
}
