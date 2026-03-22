package com.example.service;

/**
 * Sends notifications about order events.
 */
public interface NotificationService {

    void sendConfirmation(Order order);

    void sendCancellation(Order order);

    void sendShipmentUpdate(Order order, String trackingId);
}
