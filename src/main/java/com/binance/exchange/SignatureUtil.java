package com.binance.exchange;

import java.nio.charset.StandardCharsets;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

@Component
public class SignatureUtil {

	public String sign(String payload, String secretKey) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
			StringBuilder builder = new StringBuilder(digest.length * 2);
			for (byte value : digest) {
				builder.append(String.format("%02x", value));
			}
			return builder.toString();
		} catch (Exception ex) {
			throw new IllegalStateException("Unable to sign payload", ex);
		}
	}
}
