package com.Thang.ChatApp.chat;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
public class TestController {

    @GetMapping("/api/test")
    public Map<String, String> testEndpoint() {
        Map<String, String> response = new HashMap<>();
        response.put("status", "ok");
        response.put("message", "Backend is working!");
        return response;
    }
}