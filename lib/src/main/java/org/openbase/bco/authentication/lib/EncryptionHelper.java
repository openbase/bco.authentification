package org.openbase.bco.authentication.lib;

import com.google.protobuf.ByteString;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.io.StreamCorruptedException;
import java.io.UnsupportedEncodingException;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SealedObject;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.openbase.jul.exception.FatalImplementationErrorException;

/*-
 * #%L
 * BCO Authentication Library
 * %%
 * Copyright (C) 2017 openbase.org
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 * 
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
 */
/**
 * A key that is used to encrypt and decrypt tickets during Kerberos
 * authentication
 *
 * @author <a href="mailto:sfast@techfak.uni-bielefeld.de">Sebastian Fast</a>
 */
public class EncryptionHelper {

    private static final String TRANSFORMATION = "AES";
    private static final String HASH_ALGORITHM = "SHA-256";

    /**
     * Generate a 128bit key with the AES transformation which can then be used
     * for en- or decryption.
     *
     * @return the generated key as a byte array
     */
    public static byte[] generateKey() {
        try {
            KeyGenerator keyGen = KeyGenerator.getInstance(TRANSFORMATION);
            keyGen.init(128);
            SecretKey secKey = keyGen.generateKey();
            return secKey.getEncoded();
        } catch (NoSuchAlgorithmException ex) {
            new FatalImplementationErrorException("Key transformation non existent", TRANSFORMATION, ex);
            return null;
        }
    }

    /**
     * Encrypts any Object into a ByteString
     *
     * @param obj Object to be encrypted
     * @param key byte[] to encrypt obj with
     * @return Returns encrypted object as ByteString
     * @throws IOException Any IO error occurring during the serialization and
     * encryption.
     */
    public static ByteString encrypt(Serializable obj, byte[] key) throws IOException {
        try {
            // specify key
            SecretKeySpec sks = new SecretKeySpec(key, TRANSFORMATION);

            // create cipher
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, sks);
            SealedObject sealedObject = new SealedObject(obj, cipher);

            // cipher
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            CipherOutputStream cos = new CipherOutputStream(baos, cipher);
            try (ObjectOutputStream outputStream = new ObjectOutputStream(cos)) {
                outputStream.writeObject(sealedObject);
            }

            return ByteString.copyFrom(baos.toByteArray());
        } catch (NoSuchAlgorithmException | NoSuchPaddingException | IllegalBlockSizeException | InvalidKeyException ex) {
            new FatalImplementationErrorException("Key transformation non existent", TRANSFORMATION, ex);
            return null;
        }
    }

    /**
     * Decrypts a ByteString into an Object
     *
     * @param bstr ByteString to be decrypted
     * @param key byte[] to decrypt bstr with
     * @return Returns decrypted object as Object
     * @throws StreamCorruptedException If the decryption fails, because of
     * corrupted data or an invalid key.
     * @throws IOException Any other I/O failure.
     */
    public static Object decrypt(ByteString bstr, byte[] key) throws StreamCorruptedException, IOException {
        try {
            // specify key
            SecretKeySpec sks = new SecretKeySpec(key, TRANSFORMATION);

            // create cipher
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, sks);

            // decipher
            ByteArrayInputStream bais = new ByteArrayInputStream(bstr.toByteArray());
            CipherInputStream cipherInputStream = new CipherInputStream(bais, cipher);
            ObjectInputStream inputStream = new ObjectInputStream(cipherInputStream);
            SealedObject sealedObject = (SealedObject) inputStream.readObject();

            return sealedObject.getObject(cipher);
        } catch (NoSuchAlgorithmException | NoSuchPaddingException | BadPaddingException | IllegalBlockSizeException | ClassNotFoundException | InvalidKeyException ex) {
            new FatalImplementationErrorException("Decryption failed.", TRANSFORMATION, ex);
            return null;
        }
    }

    /**
     * Hashes a string symmetrically
     *
     * @param str String to be hashed
     * @return Returns a byte[] representing the hashed string
     */
    public static byte[] hash(String str) {
        try {
            byte[] key = str.getBytes("UTF-8");
            MessageDigest sha = MessageDigest.getInstance(HASH_ALGORITHM);
            key = sha.digest(key);
            return Arrays.copyOf(key, 16);
        } catch (NoSuchAlgorithmException | UnsupportedEncodingException ex) {
            new FatalImplementationErrorException("Hashing[" + str + "] failed!", HASH_ALGORITHM, ex);
            return null;
        }
    }
}
