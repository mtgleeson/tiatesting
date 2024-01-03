package org.tiatesting.vcs.perforce.connection;

import java.security.NoSuchAlgorithmException;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

public class PasswordUtil
{
    public static final String AES = "AES";
    private static String byteArrayToHexString(byte[] b)
    {
        StringBuffer sb = new StringBuffer(b.length * 2);
        for (int i = 0; i < b.length; i++) {
            int v = b[i] & 0xff;
            if (v < 16) {
                sb.append('0');
            }
            sb.append(Integer.toHexString(v));
        }
        return sb.toString().toUpperCase();
    }

    private static byte[] hexStringToByteArray(String s)
    {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            int index = i * 2;
            int v = Integer.parseInt(s.substring(index, index + 2), 16);
            b[i] = (byte) v;
        }
        return b;
    }

    public static String generateRandomKey() throws NoSuchAlgorithmException
    {
        KeyGenerator keyGen = KeyGenerator.getInstance(PasswordUtil.AES);
        keyGen.init(128);
        SecretKey sk = keyGen.generateKey();
        return byteArrayToHexString(sk.getEncoded());
    }

    public static String encryptPassword(String key, String password) throws Exception
    {
        byte[] bytekey = hexStringToByteArray(key);
        SecretKeySpec sks = new SecretKeySpec(bytekey, PasswordUtil.AES);
        Cipher cipher = Cipher.getInstance(PasswordUtil.AES);
        cipher.init(Cipher.ENCRYPT_MODE, sks, cipher.getParameters());
        byte[] encrypted = cipher.doFinal(password.getBytes());
        return byteArrayToHexString(encrypted);
    }

    public static String decryptPassword(String key, String password) throws Exception
    {
        byte[] bytekey = hexStringToByteArray(key);
        SecretKeySpec sks = new SecretKeySpec(bytekey, PasswordUtil.AES);
        Cipher cipher = Cipher.getInstance(PasswordUtil.AES);
        cipher.init(Cipher.DECRYPT_MODE, sks);
        byte[] decrypted = cipher.doFinal(hexStringToByteArray(password));
        return new String(decrypted);
    }
}
