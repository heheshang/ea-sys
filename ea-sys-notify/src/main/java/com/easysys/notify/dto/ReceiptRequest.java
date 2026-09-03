package com.easysys.notify.dto;

/**
 * 通道异步回执：真实供应商投递完成后 webhook 回调 notify（dev 由内部模拟产生）。
 * status ∈ DELIVERED / FAILED。
 */
public record ReceiptRequest(
        String channelMsgId,
        String status,
        String error) {
}