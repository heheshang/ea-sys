package com.easysys.notify.controller;

import com.easysys.notify.dto.DeliverRequest;
import com.easysys.notify.dto.ReceiptRequest;
import com.easysys.notify.service.NotifyService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 回调服务入站端点：
 * - POST /api/notify/deliver：主服务受理成功后投递真正触达任务
 * - POST /api/notify/receipt：通道异步回执 webhook（dev 由模拟产生，生产接真实供应商回调）
 */
@RestController
@RequestMapping("/api/notify")
public class NotifyController {

    private final NotifyService notifyService;

    public NotifyController(NotifyService notifyService) {
        this.notifyService = notifyService;
    }

    @PostMapping("/deliver")
    public ResponseEntity<Void> deliver(@RequestBody DeliverRequest request) {
        notifyService.deliver(request);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/receipt")
    public ResponseEntity<Void> receipt(@RequestBody ReceiptRequest receipt) {
        notifyService.receipt(receipt);
        return ResponseEntity.accepted().build();
    }
}