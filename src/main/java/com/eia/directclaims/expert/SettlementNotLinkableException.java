package com.eia.directclaims.expert;

/**
 * A settlement in any state other than AUTHORIZED or EXECUTED cannot be linked
 * to a Payment record.
 */
public class SettlementNotLinkableException extends RuntimeException {
    public SettlementNotLinkableException(String message) {
        super(message);
    }
}
