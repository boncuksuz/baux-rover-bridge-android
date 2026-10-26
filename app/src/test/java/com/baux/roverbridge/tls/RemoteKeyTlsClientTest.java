package com.baux.roverbridge.tls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.engines.RSABlindedEngine;
import org.bouncycastle.crypto.generators.MGF1BytesGenerator;
import org.bouncycastle.crypto.params.MGFParameters;
import org.bouncycastle.crypto.params.ParametersWithRandom;
import org.bouncycastle.crypto.params.RSAKeyParameters;
import org.bouncycastle.crypto.util.PrivateKeyFactory;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.tls.TlsClientProtocol;
import org.junit.Test;

/** Only ephemeral test keys; the Android production APK contains no private key. */
public class RemoteKeyTlsClientTest {
    private static final char[] PASSWORD = "local-only".toCharArray();

    @Test
    public void remotePssSignerCompletesLocalTls12AndCountsNtripBody() throws Exception {
        KeyPair rootKeys = rsaKeys();
        KeyPair serverKeys = rsaKeys();
        KeyPair clientKeys = rsaKeys();
        X509Certificate root = certificate(rootKeys, "CN=Local Root", null, rootKeys, true, null);
        X509Certificate server = certificate(serverKeys, "CN=localhost", root, rootKeys,
                false, new GeneralName(GeneralName.dNSName, "localhost"));
        X509Certificate client = certificate(clientKeys, "CN=Local Client", null, clientKeys,
                false, null);

        SSLContext context = serverContext(serverKeys, server, root, client);
        try (SSLServerSocket listener = (SSLServerSocket) context.getServerSocketFactory()
                .createServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            listener.setEnabledProtocols(new String[]{"TLSv1.2"});
            listener.setEnabledCipherSuites(new String[]{"TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"});
            listener.setNeedClientAuth(true);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<Boolean> serverResult = executor.submit(() -> serve(listener));
                AtomicInteger hashLength = new AtomicInteger();
                ServerCertificateValidator validator = new ServerCertificateValidator(root,
                        "localhost", false);
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(),
                            listener.getLocalPort()), 3000);
                    socket.setSoTimeout(5000);
                    TlsClientProtocol protocol = new RemoteKeyTlsClient().connect(socket, client,
                            hash -> {
                                hashLength.set(hash.length);
                                return signPrehashedPss(clientKeys, hash);
                            }, validator);
                    try {
                        socket.setSoTimeout(200);
                        String localConfig = "host=localhost\nport=443\nmountpoint=/LOCAL\n"
                                + "authorizationBase64=dGVzdDp0ZXN0\n";
                        NtripConfiguration config = NtripConfiguration.Companion.read(
                                new java.io.ByteArrayInputStream(
                                        localConfig.getBytes(StandardCharsets.US_ASCII)));
                        protocol.getOutputStream().write(config.requestBytes());
                        protocol.getOutputStream().flush();
                        NtripResult response = NtripResponseReader.INSTANCE.read(
                                protocol.getInputStream(), 1);
                        assertTrue(response.getSuccess());
                        assertEquals("ICY 200", response.getSafeStatus());
                        assertEquals(8L, response.getBodyBytes());
                        assertEquals(32, hashLength.get());
                        assertEquals("DNS subjectAltName", validator.identityMethod());
                        assertTrue(serverResult.get(5, TimeUnit.SECONDS));
                    } finally {
                        protocol.close();
                    }
                }
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    public void legacyIpCnRequiresExplicitOptInAndNoIpSan() throws Exception {
        KeyPair rootKeys = rsaKeys();
        KeyPair serverKeys = rsaKeys();
        X509Certificate root = certificate(rootKeys, "CN=Local Root", null, rootKeys, true, null);
        X509Certificate leaf = certificate(serverKeys, "CN=127.0.0.1", root, rootKeys,
                false, null);
        new ServerCertificateValidator(root, "127.0.0.1", true)
                .verify(Arrays.asList(leaf, root));
        expectCertificateFailure(() -> new ServerCertificateValidator(root, "127.0.0.1", false)
                .verify(Arrays.asList(leaf, root)));

        X509Certificate wrongSan = certificate(serverKeys, "CN=127.0.0.1", root, rootKeys,
                false, new GeneralName(GeneralName.iPAddress, "192.0.2.1"));
        expectCertificateFailure(() -> new ServerCertificateValidator(root, "127.0.0.1", true)
                .verify(Arrays.asList(wrongSan, root)));
    }

    private interface CertificateAction { void run() throws Exception; }

    private static void expectCertificateFailure(CertificateAction action) throws Exception {
        try {
            action.run();
            fail("Expected certificate identity rejection");
        } catch (CertificateException expected) {
            // Expected.
        }
    }

    private static boolean serve(SSLServerSocket listener) throws Exception {
        try (SSLSocket socket = (SSLSocket) listener.accept()) {
            socket.setSoTimeout(5000);
            socket.startHandshake();
            boolean clientCertificate = socket.getSession().getPeerCertificates().length > 0;
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.US_ASCII));
            boolean request = "GET /LOCAL HTTP/1.0".equals(reader.readLine());
            boolean authorization = false;
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                if ("Authorization: Basic dGVzdDp0ZXN0".equals(line)) authorization = true;
            }
            socket.getOutputStream().write("ICY 200 OK\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().write(new byte[]{(byte) 0xD3, 0, 2, 1, 2, 3, 4, 5});
            socket.getOutputStream().flush();
            Thread.sleep(1500);
            return clientCertificate && request && authorization;
        }
    }

    private static SSLContext serverContext(KeyPair keys, X509Certificate server,
            X509Certificate root, X509Certificate client) throws Exception {
        KeyStore identity = KeyStore.getInstance("PKCS12");
        identity.load(null, null);
        identity.setKeyEntry("server", keys.getPrivate(), PASSWORD,
                new java.security.cert.Certificate[]{server, root});
        KeyStore trustedClients = KeyStore.getInstance("PKCS12");
        trustedClients.load(null, null);
        trustedClients.setCertificateEntry("client", client);
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(identity, PASSWORD);
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(trustedClients);
        SSLContext context = SSLContext.getInstance("TLSv1.2");
        context.init(keyManagers.getKeyManagers(), trustManagers.getTrustManagers(), new SecureRandom());
        return context;
    }

    private static KeyPair rsaKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static X509Certificate certificate(KeyPair subjectKeys, String subjectName,
            X509Certificate issuer, KeyPair issuerKeys, boolean ca, GeneralName san) throws Exception {
        long now = System.currentTimeMillis();
        X500Name subject = new X500Name(subjectName);
        X500Name issuerName = issuer == null ? subject
                : X500Name.getInstance(issuer.getSubjectX500Principal().getEncoded());
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                issuerName, BigInteger.valueOf(now).add(BigInteger.valueOf(subjectName.hashCode() & 0xffff)),
                new Date(now - 60000), new Date(now + 3600000), subject, subjectKeys.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(ca));
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(ca
                ? KeyUsage.keyCertSign : KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
        if (!ca) {
            builder.addExtension(Extension.extendedKeyUsage, false,
                    new ExtendedKeyUsage(new KeyPurposeId[]{KeyPurposeId.id_kp_serverAuth,
                            KeyPurposeId.id_kp_clientAuth}));
        }
        if (san != null) {
            builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(san));
        }
        return new JcaX509CertificateConverter().getCertificate(
                builder.build(new JcaContentSignerBuilder("SHA256withRSA")
                        .build(issuerKeys.getPrivate())));
    }

    /** Test-only equivalent of the ESP32 prehashed 32-byte RSA-PSS operation. */
    private static byte[] signPrehashedPss(KeyPair keys, byte[] hash) throws java.io.IOException {
        if (hash.length != 32) throw new java.io.IOException("Expected 32-byte hash");
        try {
            RSAKeyParameters privateKey = (RSAKeyParameters) PrivateKeyFactory.createKey(
                    keys.getPrivate().getEncoded());
            SecureRandom random = new SecureRandom();
            byte[] salt = new byte[32];
            random.nextBytes(salt);
            byte[] mPrime = new byte[8 + hash.length + salt.length];
            System.arraycopy(hash, 0, mPrime, 8, hash.length);
            System.arraycopy(salt, 0, mPrime, 8 + hash.length, salt.length);
            byte[] h = new byte[32];
            SHA256Digest digest = new SHA256Digest();
            digest.update(mPrime, 0, mPrime.length);
            digest.doFinal(h, 0);
            int emBits = privateKey.getModulus().bitLength() - 1;
            int emLength = (emBits + 7) / 8;
            int dbLength = emLength - 33;
            byte[] db = new byte[dbLength];
            db[dbLength - salt.length - 1] = 1;
            System.arraycopy(salt, 0, db, dbLength - salt.length, salt.length);
            byte[] mask = new byte[dbLength];
            MGF1BytesGenerator mgf = new MGF1BytesGenerator(new SHA256Digest());
            mgf.init(new MGFParameters(h));
            mgf.generateBytes(mask, 0, mask.length);
            byte[] encoded = new byte[emLength];
            for (int i = 0; i < dbLength; i++) encoded[i] = (byte) (db[i] ^ mask[i]);
            encoded[0] &= (byte) (0xff >>> (8 * emLength - emBits));
            System.arraycopy(h, 0, encoded, dbLength, h.length);
            encoded[emLength - 1] = (byte) 0xbc;
            RSABlindedEngine rsa = new RSABlindedEngine();
            rsa.init(true, new ParametersWithRandom(privateKey, random));
            return rsa.processBlock(encoded, 0, encoded.length);
        } catch (Exception e) {
            throw new java.io.IOException("Test RSA-PSS signing failed", e);
        }
    }
}
