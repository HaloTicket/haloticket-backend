package com.haloticket.payment_service.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class PayoutPolicyController {

    @RequestMapping({"/api/payouts/admin/policy", "/api/payouts/admin/policy/**"})
    public void retiredAdvancePolicy() {
        throw new ResponseStatusException(HttpStatus.GONE, "Advance payouts are no longer supported");
    }
}
