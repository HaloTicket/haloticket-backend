package com.haloticket.marketplace_service.authz;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "authz.event-scope")
public class EventScopeProperties {

    private long cacheSeconds = 30;
    private long staleCheckinSeconds = 300;

    public long getCacheSeconds() { return cacheSeconds; }
    public void setCacheSeconds(long cacheSeconds) { this.cacheSeconds = cacheSeconds; }
    public long getStaleCheckinSeconds() { return staleCheckinSeconds; }
    public void setStaleCheckinSeconds(long staleCheckinSeconds) { this.staleCheckinSeconds = staleCheckinSeconds; }
}
