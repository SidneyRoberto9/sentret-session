package io.github.sidneyroberto9.sentret.service;

import io.github.sidneyroberto9.sentret.config.SentretProperties;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

public class SentretIpHasher {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    public SentretIpHasher(SentretProperties properties) {
        this.key = new SecretKeySpec(properties.getIpHashSalt().getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    public String hash(String ip) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);

            byte[] result = mac.doFinal(ip.getBytes(StandardCharsets.UTF_8));

            return HexFormat.of().formatHex(result);
        } catch (Exception e) {
            throw new IllegalStateException("IP hashing failed", e);
        }
    }
}
