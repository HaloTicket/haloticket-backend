package com.haloticket.payment_service.authz;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "authz.event-scope")
public class PaymentScopeProperties {

    private String internalToken = "";

    public String getInternalToken() { return internalToken; }
    public void setInternalToken(String internalToken) { this.internalToken = internalToken == null ? "" : internalToken; }
}
