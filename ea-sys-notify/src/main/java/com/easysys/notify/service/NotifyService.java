package com.easysys.notify.service;

import com.easysys.notify.dto.DeliverRequest;
import com.easysys.notify.dto.ReceiptRequest;

/**
 * 通道回执入站服务：登记待确认触达，模拟真实通道异步回执（dev），
 * 确认「真正触达」后回调主服务更新 delivery_record。
 */
public interface NotifyService {

    /** 受理主服务投递请求，登记后异步执行真正触达与回执。 */
    void deliver(DeliverRequest request);

    /** 接收通道异步回执（真实供应商 webhook / dev 内部模拟），确认后回调主服务。 */
    void receipt(ReceiptRequest receipt);
}