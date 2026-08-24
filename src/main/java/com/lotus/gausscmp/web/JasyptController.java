package com.lotus.gausscmp.web;

import org.jasypt.encryption.pbe.StandardPBEStringEncryptor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/jasypt")
@CrossOrigin
public class JasyptController {
    private static final String ALGORITHM = "PBEWithMD5AndDES";

    public record EncryptRequest(String key, String plaintext) {}

    @PostMapping("/encrypt")
    public Map<String, String> encrypt(@RequestBody EncryptRequest request) {
        if (request == null || request.key() == null || request.key().isBlank()) {
            throw new IllegalArgumentException("请输入 Jasypt 主密钥");
        }
        if (request.plaintext() == null || request.plaintext().isEmpty()) {
            throw new IllegalArgumentException("请输入密码明文");
        }
        if (request.key().length() > 4096 || request.plaintext().length() > 4096) {
            throw new IllegalArgumentException("输入长度不能超过 4096 个字符");
        }

        var encryptor = new StandardPBEStringEncryptor();
        encryptor.setAlgorithm(ALGORITHM);
        encryptor.setStringOutputType("base64");
        encryptor.setPassword(request.key());
        String cipher = encryptor.encrypt(request.plaintext());
        return Map.of("ciphertext", "ENC(" + cipher + ")", "algorithm", ALGORITHM);
    }
}
