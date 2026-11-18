package com.haloticket.marketplace_service.enums;
/**
 * Trạng thái của saga chuyển nhượng: chờ khóa vé, chờ người nhận, chờ chuyển vé, hoàn tất, thất bại,
 * đang bù trừ và đã bù trừ.
 */
public enum SagaState { WAITING_LOCK, WAITING_RECIPIENT, WAITING_TRANSFER, COMPLETED, FAILED, COMPENSATING, COMPENSATED }
