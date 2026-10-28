package com.haloticket.event_service.authz;

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
    private boolean legacyFallback = true;
    private Group catalog = new Group();
    private Group orders = new Group();
    private Group vouchers = new Group();
    private Group checkin = new Group();
    private Group members = new Group();
    private Group identity = new Group();
    private Group permissionFunction = new Group();
    private Group notifications = new Group();

    private boolean on(Group group) { return enabled && group.isEnabled(); }

    public boolean catalogOn() { return on(catalog); }
    public boolean ordersOn() { return on(orders); }
    public boolean vouchersOn() { return on(vouchers); }
    public boolean checkinOn() { return on(checkin); }
    public boolean membersOn() { return on(members); }
    public boolean identityOn() { return on(identity); }
    public boolean permissionFunctionOn() { return on(permissionFunction); }
    public boolean notificationsOn() { return on(notifications); }
    public boolean legacyFallbackOn() { return legacyFallback; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isLegacyFallback() { return legacyFallback; }
    public void setLegacyFallback(boolean legacyFallback) { this.legacyFallback = legacyFallback; }
    public Group getCatalog() { return catalog; }
    public void setCatalog(Group catalog) { this.catalog = catalog; }
    public Group getOrders() { return orders; }
    public void setOrders(Group orders) { this.orders = orders; }
    public Group getVouchers() { return vouchers; }
    public void setVouchers(Group vouchers) { this.vouchers = vouchers; }
    public Group getCheckin() { return checkin; }
    public void setCheckin(Group checkin) { this.checkin = checkin; }
    public Group getMembers() { return members; }
    public void setMembers(Group members) { this.members = members; }
    public Group getIdentity() { return identity; }
    public void setIdentity(Group identity) { this.identity = identity; }
    public Group getPermissionFunction() { return permissionFunction; }
    public void setPermissionFunction(Group permissionFunction) { this.permissionFunction = permissionFunction; }
    public Group getNotifications() { return notifications; }
    public void setNotifications(Group notifications) { this.notifications = notifications; }
}
