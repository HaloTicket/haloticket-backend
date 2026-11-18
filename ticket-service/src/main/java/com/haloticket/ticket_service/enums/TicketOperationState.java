package com.haloticket.ticket_service.enums;
/** Trạng thái của một thao tác trên vé: vừa yêu cầu, đã khóa, yêu cầu nhả, đã nhả, đã chuyển quyền, bị từ chối hoặc đã hủy. */
public enum TicketOperationState { REQUESTED, LOCKED, RELEASE_REQUESTED, RELEASED, TRANSFERRED, REJECTED, CANCELLED }
