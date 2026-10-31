/*
 * Copyright (c) 2026 - present - Francois Papon - francois.papon@openobject.fr
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package fr.openobject.ai.syleks.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Versioned authenticated ciphertext; key material is never stored with configuration. */
final class EncryptedConfigValue {
    private static final String PREFIX = "syleks-encrypted:";
    private static final String VERSION = PREFIX + "v1:";
    private static final int NONCE_BYTES = 12;

    private EncryptedConfigValue() {}

    static boolean matches(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    private static SecretKeySpec key(String encoded) throws IOException {
        byte[] bytes = null;
        try {
            if (encoded == null || encoded.length() != 44) throw new IllegalArgumentException();
            bytes = Base64.getDecoder().decode(encoded);
            if (bytes.length != 32) throw new IllegalArgumentException();
            return new SecretKeySpec(bytes, "AES");
        } catch (IllegalArgumentException e) {
            throw new IOException("SYLEKS_CONFIG_KEY must contain a Base64-encoded 32-byte key");
        } finally {
            if (bytes != null) Arrays.fill(bytes, (byte) 0);
        }
    }

    static String encrypt(String secret, String scope, String encodedKey) throws IOException {
        var key = key(encodedKey);
        byte[] plaintext = secret.getBytes(StandardCharsets.UTF_8);
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            new SecureRandom().nextBytes(nonce);
            var cipher = cipher(Cipher.ENCRYPT_MODE, key, nonce, scope);
            byte[] encrypted = cipher.doFinal(plaintext);
            byte[] payload = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, payload, 0, nonce.length);
            System.arraycopy(encrypted, 0, payload, nonce.length, encrypted.length);
            return VERSION + Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException e) {
            throw new IOException("Configuration encryption failed");
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    static String decrypt(String value, String scope, String encodedKey) throws IOException {
        var key = key(encodedKey);
        byte[] plaintext = null;
        try {
            if (!value.startsWith(VERSION) || value.length() > 50000)
                throw new IllegalArgumentException();
            byte[] payload = Base64.getDecoder().decode(value.substring(VERSION.length()));
            if (payload.length < NONCE_BYTES + 16) throw new IllegalArgumentException();
            var cipher =
                    cipher(Cipher.DECRYPT_MODE, key, Arrays.copyOf(payload, NONCE_BYTES), scope);
            plaintext = cipher.doFinal(payload, NONCE_BYTES, payload.length - NONCE_BYTES);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // Never retain exceptions that may include user-controlled input.
            throw new IOException("Encrypted configuration value unavailable or invalid");
        } finally {
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
        }
    }

    private static Cipher cipher(int mode, SecretKeySpec key, byte[] nonce, String scope)
            throws GeneralSecurityException {
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(128, nonce));
        cipher.updateAAD((VERSION + scope).getBytes(StandardCharsets.UTF_8));
        return cipher;
    }
}
