package com.haloticket.event_service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haloticket.event_service.dto.request.EventCreateRequest;

/** Chương trình chạy tay để thử đọc JSON có {@code customQuestions} thành {@code EventCreateRequest}. */
public class TestJson {
    /**
     * Đọc một chuỗi JSON mẫu vào {@code EventCreateRequest} và in danh sách câu hỏi tùy chỉnh ra màn hình.
     * @param args không dùng
     * @throws Exception khi không đọc được JSON
     */
    public static void main(String[] args) throws Exception {
        String json = "{\"customQuestions\": [{\"id\":\"q_123\", \"title\":\"abc\"}]}";
        ObjectMapper mapper = new ObjectMapper();
        EventCreateRequest req = mapper.readValue(json, EventCreateRequest.class);
        System.out.println(req.getCustomQuestions());
    }
}
