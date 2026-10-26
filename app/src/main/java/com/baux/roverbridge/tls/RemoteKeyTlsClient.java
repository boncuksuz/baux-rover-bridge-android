package com.baux.roverbridge.tls;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.Socket;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;

import org.bouncycastle.tls.AlertDescription;
import org.bouncycastle.tls.Certificate;
import org.bouncycastle.tls.CertificateRequest;
import org.bouncycastle.tls.CipherSuite;
import org.bouncycastle.tls.DefaultTlsClient;
import org.bouncycastle.tls.ProtocolVersion;
import org.bouncycastle.tls.SignatureAndHashAlgorithm;
import org.bouncycastle.tls.SignatureScheme;
import org.bouncycastle.tls.TlsAuthentication;
import org.bouncycastle.tls.TlsClientProtocol;
import org.bouncycastle.tls.TlsCredentialedSigner;
import org.bouncycastle.tls.TlsCredentials;
import org.bouncycastle.tls.TlsFatalAlert;
import org.bouncycastle.tls.TlsServerCertificate;
import org.bouncycastle.tls.crypto.TlsCertificate;
import org.bouncycastle.tls.crypto.TlsStreamSigner;
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto;

/** TLS 1.2 client with CertificateVerify delegated to the existing ESP32 SPP session. */
public final class RemoteKeyTlsClient {
    private static final int[] CIPHER_SUITES = {
            CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
            CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256
    };

    public interface RemoteSigner {
        byte[] sign(byte[] sha256Hash) throws IOException;
    }

    public TlsClientProtocol connect(Socket socket, X509Certificate clientCertificate,
            RemoteSigner signer, ServerCertificateValidator validator) throws IOException {
        BcTlsCrypto crypto = new BcTlsCrypto(new SecureRandom());
        final Certificate chain;
        try {
            chain = new Certificate(new TlsCertificate[]{
                    crypto.createCertificate(clientCertificate.getEncoded())
            });
        } catch (CertificateException e) {
            throw new IOException("Client certificate could not be encoded", e);
        }

        TlsClientProtocol protocol = new TlsClientProtocol(socket.getInputStream(), socket.getOutputStream());
        protocol.connect(new DefaultTlsClient(crypto) {
            @Override
            public ProtocolVersion[] getProtocolVersions() {
                return new ProtocolVersion[]{ProtocolVersion.TLSv12};
            }

            @Override
            public int[] getCipherSuites() {
                return CIPHER_SUITES.clone();
            }

            @Override
            public TlsAuthentication getAuthentication() {
                return new TlsAuthentication() {
                    @Override
                    public void notifyServerCertificate(TlsServerCertificate server) throws IOException {
                        try {
                            validator.verify(decodeServerChain(server));
                        } catch (CertificateException e) {
                            throw new TlsFatalAlert(AlertDescription.bad_certificate, e);
                        }
                    }

                    @Override
                    public TlsCredentials getClientCredentials(CertificateRequest request) throws IOException {
                        if (!offersPssSha256(request.getSupportedSignatureAlgorithms())) {
                            throw new TlsFatalAlert(AlertDescription.handshake_failure);
                        }
                        SignatureAndHashAlgorithm algorithm = SignatureScheme
                                .getSignatureAndHashAlgorithm(SignatureScheme.rsa_pss_rsae_sha256);
                        return new TlsCredentialedSigner() {
                            @Override
                            public Certificate getCertificate() { return chain; }

                            @Override
                            public byte[] generateRawSignature(byte[] hash) throws IOException {
                                return signer.sign(hash);
                            }

                            @Override
                            public SignatureAndHashAlgorithm getSignatureAndHashAlgorithm() {
                                return algorithm;
                            }

                            @Override
                            public TlsStreamSigner getStreamSigner() { return null; }
                        };
                    }
                };
            }
        });
        return protocol;
    }

    private static boolean offersPssSha256(Vector algorithms) {
        if (algorithms == null) return false;
        for (Object offered : algorithms) {
            if (offered instanceof SignatureAndHashAlgorithm
                    && SignatureScheme.from((SignatureAndHashAlgorithm) offered)
                    == SignatureScheme.rsa_pss_rsae_sha256) {
                return true;
            }
        }
        return false;
    }

    private static List<X509Certificate> decodeServerChain(TlsServerCertificate server)
            throws IOException, CertificateException {
        TlsCertificate[] certificates = server.getCertificate().getCertificateList();
        if (certificates == null || certificates.length == 0) {
            throw new CertificateException("Server sent no certificate");
        }
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        List<X509Certificate> chain = new ArrayList<>(certificates.length);
        for (TlsCertificate certificate : certificates) {
            chain.add((X509Certificate) factory.generateCertificate(
                    new ByteArrayInputStream(certificate.getEncoded())));
        }
        return chain;
    }
}
