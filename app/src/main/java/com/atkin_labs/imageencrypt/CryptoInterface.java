package com.atkin_labs.imageencrypt;

import android.content.Context;
import android.security.KeyPairGeneratorSpec;
import android.support.v4.util.TimeUtils;
import android.util.Base64;
import android.util.Log;

import org.spongycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.spongycastle.asn1.x500.X500Name;
import org.spongycastle.asn1.x500.X500NameBuilder;
import org.spongycastle.asn1.x500.style.BCStyle;
import org.spongycastle.asn1.x509.AlgorithmIdentifier;
import org.spongycastle.asn1.x509.SubjectPublicKeyInfo;
import org.spongycastle.cert.X509v1CertificateBuilder;
import org.spongycastle.cert.X509v3CertificateBuilder;
import org.spongycastle.cert.jcajce.JcaX509CertificateConverter;
import org.spongycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.spongycastle.crypto.engines.RSAEngine;
import org.spongycastle.operator.ContentSigner;
import org.spongycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.spec.X509EncodedKeySpec;
import java.util.Calendar;
import java.util.Date;
import java.util.Enumeration;

import javax.crypto.Cipher;
import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.PBEParameterSpec;

import static java.util.GregorianCalendar.BC;

/**
 * Created by Spencer on 1/24/17.
 */

public class CryptoInterface {
    private final String PRIVATE_KEY_ALIAS = "com.atkin-labs.RSAPrivateKeyAlias";
    private final String KEYSTORE_LOCATION = "com.atkin-labs.keystore_name";
    private static CryptoInterface sCryptoInterface;
    private KeyStore keyStore = null;

    static {
        Security.insertProviderAt(new org.spongycastle.jce.provider.BouncyCastleProvider(), 1);
    }

    public CryptoInterface(File keyStoreDir, String keyStorePassword, String privKeyPassword) {
        FileOutputStream fos = null;
        try {
            keyStore = loadKeyStore(keyStorePassword, keyStoreDir);
            if (keyStore == null) {
                return;
            }
            Log.e("ImageEncrypt", keyStore.toString());
            if (!keyStore.containsAlias(PRIVATE_KEY_ALIAS)) {
                KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA", "SC");
                gen.initialize(4096, new SecureRandom());
                KeyPair keyPair = gen.genKeyPair();

                X500NameBuilder builder=new X500NameBuilder(BCStyle.INSTANCE);
                //builder.addRDN(BCStyle.OU, );
                builder.addRDN(BCStyle.O, "Atkin Labs");
                builder.addRDN(BCStyle.CN, "atkin-labs.com");
                Calendar c = Calendar.getInstance();
                c.add(Calendar.DATE, -1);
                Date notBefore = c.getTime();
                c.add(Calendar.DATE, 1);
                c.add(Calendar.YEAR, 10);
                Date notAfter = c.getTime();
                BigInteger serial = BigInteger.valueOf(System.currentTimeMillis());
                X509v3CertificateBuilder certGen = new JcaX509v3CertificateBuilder(builder.build(), serial, notBefore, notAfter, builder.build(), keyPair.getPublic());
                ContentSigner sigGen = new JcaContentSignerBuilder("SHA256WithRSAEncryption").setProvider("SC").build(keyPair.getPrivate());
                Certificate cert = new JcaX509CertificateConverter().setProvider("SC").getCertificate(certGen.build(sigGen));
                ((X509Certificate)cert).checkValidity(new Date());
                cert.verify(cert.getPublicKey());

                KeyStore.PrivateKeyEntry privateKeyEntry = new KeyStore.PrivateKeyEntry(keyPair.getPrivate(), new Certificate[] {cert});
                keyStore.setEntry(PRIVATE_KEY_ALIAS, privateKeyEntry, new KeyStore.PasswordProtection(privKeyPassword.toCharArray()));
                // Save the keystore to file
                fos = new FileOutputStream(new File(keyStoreDir, KEYSTORE_LOCATION));
                keyStore.store(fos, keyStorePassword.toCharArray());

            }
        }
        catch (Exception e) {
            e.printStackTrace();
        }
        finally {
            if (fos != null) {
                try {
                    fos.close();
                }
                catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public PublicKey getPublicKey(String privKeyPassword) {
        if (keyStore == null) {
            return null;
        }
        PublicKey publicKey = null;
        try {
            KeyStore.PrivateKeyEntry privateKeyEntry = (KeyStore.PrivateKeyEntry) keyStore.getEntry(PRIVATE_KEY_ALIAS, new KeyStore.PasswordProtection(privKeyPassword.toCharArray()));
            publicKey = privateKeyEntry.getCertificate().getPublicKey();
        }
        catch (Exception e) {
            e.printStackTrace();
        }
        return publicKey;
    }

    public PublicKey publicKeyFromBytes(byte[] bytes) {
        PublicKey publicKey = null;
        try {
            publicKey = KeyFactory.getInstance("RSA", "SC").generatePublic(new X509EncodedKeySpec(bytes));
        }
        catch (Exception e) {
            e.printStackTrace();
        }
        return publicKey;
    }

    public String encrypt(byte[] plaintext, PublicKey publicKey) {
        String cipherText = null;
        try {
            // Generate random AES key to be used only once
            KeyGenerator keyGen = KeyGenerator.getInstance("AES", "SC");
            keyGen.init(256);
            SecretKey randomAES = keyGen.generateKey();
            final Cipher aesCipher = Cipher.getInstance("AES/CBC/PKCS5Padding", "SC");
            byte[] iv = new byte[16];
            SecureRandom random = new SecureRandom();
            random.nextBytes(iv);
            IvParameterSpec ivParameterSpec = new IvParameterSpec(iv);
            aesCipher.init(Cipher.ENCRYPT_MODE, randomAES, ivParameterSpec);

            // Encrypt the plaintext with the random AES key
            byte[] cipherTextBytes = aesCipher.doFinal(plaintext);

            // Create an RSA cipher with the public key
            final Cipher rsaCipher = Cipher.getInstance("RSA", "SC");
            rsaCipher.init(Cipher.ENCRYPT_MODE, publicKey);

            // Encrypt the AES key with the RSA cipher
            byte[] encryptedKeyBytes = rsaCipher.doFinal(randomAES.getEncoded());

            // Concatenate the encrypted AES key and the encrypted plaintext and base64 them
            byte[] combinedCipherTextBytes = new byte[encryptedKeyBytes.length + cipherTextBytes.length]; // Concatenated byte arrays
            System.arraycopy(encryptedKeyBytes, 0, combinedCipherTextBytes, 0, encryptedKeyBytes.length);
            System.arraycopy(cipherTextBytes, 0, combinedCipherTextBytes, encryptedKeyBytes.length, cipherTextBytes.length);

            cipherText = Base64.encodeToString(combinedCipherTextBytes, Base64.DEFAULT);
        }
        catch (Exception e) {
            e.printStackTrace();
        }
        return cipherText;
    }

    public String decrypt(String base64CipherText, String privKeyPassword) {
        if (keyStore == null) {
            return null;
        }

        String plainText = null;
        try {
            KeyStore.PrivateKeyEntry privateKeyEntry = (KeyStore.PrivateKeyEntry)keyStore.getEntry(PRIVATE_KEY_ALIAS, new KeyStore.PasswordProtection(privKeyPassword.toCharArray()));
            PrivateKey privateKey = privateKeyEntry.getPrivateKey();

            final Cipher cipher = Cipher.getInstance("RSA", "SC");

            cipher.init(Cipher.DECRYPT_MODE, privateKey);
            plainText = new String(cipher.doFinal(Base64.decode(base64CipherText, Base64.DEFAULT)));
            privateKey = null;
            Log.d("ImageEncrypt", plainText);
        }
        catch (Exception e) {
            e.printStackTrace();
        }
        return plainText;
    }

    private KeyStore loadKeyStore(String password, File dir) {
        File f = new File(dir, KEYSTORE_LOCATION);
        KeyStore ks = null;
        try {
            ks = KeyStore.getInstance(KeyStore.getDefaultType());

            // If keystore does not yet exist, create a new one
            if (!f.exists()) {
                Log.d("ImageEncrypt", "Creating new keystore");
                ks.load(null);
            }
            // Otherwise load it from file
            else {
                Log.d("ImageEncrypt", "Loading keystore from file");
                FileInputStream fis = new FileInputStream(f);
                ks.load(fis, password.toCharArray());
                fis.close();
            }
        }
        catch (Exception e) {
            e.printStackTrace();
        }
        return ks;
    }
}
