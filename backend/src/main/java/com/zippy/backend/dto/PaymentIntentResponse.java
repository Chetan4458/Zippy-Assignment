package com.zippy.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.math.BigDecimal;

public class PaymentIntentResponse {
    private String paymentId;
    private String orderId;
    private BigDecimal amount;
    private String currency;
    private String status;
    private String paymentMethod;
    private String collectionStage;
    private String failureCode;
    private String failureReason;
    private BigDecimal refundedAmount;
    private LocalDateTime createdAt;

    public String getPaymentId(){
        return paymentId;
    }
    public void setPaymentId(String paymentId){
        this.paymentId=paymentId;
    }
    public String getOrderId(){
        return orderId;
    }
    public void setOrderId(String orderId){
        this.orderId=orderId;
    }
    public BigDecimal getAmount(){
        return amount;
    }

    public void setAmount(BigDecimal amount){
        this.amount=amount;
    }
    public String getCurrency(){
        return currency;
    }
    public void setCurrency(String currency){
        this.currency=currency;
    }
    public String getStatus(){
        return status;
    }
    public void setStatus(String status){
        this.status=status;
    }
    public String getPaymentMethod(){
        return paymentMethod;
    }
    public void setPaymentMethod(String paymentMethod){
        this.paymentMethod=paymentMethod;
    }
    public String getCollectionStage(){
        return collectionStage;
    }
    public void setCollectionStage(String collectionStage){
        this.collectionStage=collectionStage;
    }
    public String getFailureCode(){
        return failureCode;
    }
    public void setFailureCode(String failureCode){
        this.failureCode=failureCode;
    }
    public String getFailureReason(){
        return failureReason;
    }
    public void setFailureReason(String failureReason){
        this.failureReason=failureReason;
    }
    public BigDecimal getRefundedAmount(){
        return refundedAmount;
    }
    public void setRefundedAmount(BigDecimal refundedAmount){
        this.refundedAmount=refundedAmount;
    }
    public LocalDateTime getCreatedAt(){
        return createdAt;
    }
    public void setCreatedAt(LocalDateTime createdAt){
        this.createdAt=createdAt;
    }
}
