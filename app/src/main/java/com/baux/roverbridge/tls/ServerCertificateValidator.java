package com.baux.roverbridge.tls;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.bouncycastle.asn1.x500.RDN;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x500.style.IETFUtils;

/** PKIX trust plus exact server identity, with explicit legacy IP-CN fallback. */
public final class ServerCertificateValidator {
    private final X509Certificate trustedCa;
    private final String expectedHost;
    private final boolean allowIpCnFallback;
    private volatile String identityMethod = "not verified";

    public ServerCertificateValidator(X509Certificate trustedCa, String expectedHost,
            boolean allowIpCnFallback) {
        this.trustedCa = trustedCa;
        this.expectedHost = expectedHost;
        this.allowIpCnFallback = allowIpCnFallback;
    }

    public String identityMethod() { return identityMethod; }

    public void verify(List<X509Certificate> chain) throws CertificateException {
        if (chain == null || chain.isEmpty()) {
            throw new CertificateException("Server sent no certificate");
        }
        X509Certificate leaf = chain.get(0);
        leaf.checkValidity();
        verifyIdentity(leaf);
        verifyPath(chain);
    }

    private void verifyPath(List<X509Certificate> chain) throws CertificateException {
        List<X509Certificate> pathCertificates = new ArrayList<>(chain);
        pathCertificates.removeIf(trustedCa::equals);
        if (pathCertificates.isEmpty()) {
            throw new CertificateException("Server certificate chain has no leaf");
        }
        try {
            CertPath path = CertificateFactory.getInstance("X.509")
                    .generateCertPath(pathCertificates);
            PKIXParameters parameters = new PKIXParameters(
                    Collections.singleton(new TrustAnchor(trustedCa, null)));
            // Mirrors the verified desktop prototype. Revocation is not checked in this test.
            parameters.setRevocationEnabled(false);
            CertPathValidator.getInstance("PKIX").validate(path, parameters);
        } catch (GeneralSecurityException e) {
            throw new CertificateException("Server certificate PKIX validation failed", e);
        }
    }

    private void verifyIdentity(X509Certificate leaf) throws CertificateException {
        boolean ipHost = isIpLiteral(expectedHost);
        boolean ipSanPresent = false;
        Collection<List<?>> names = leaf.getSubjectAlternativeNames();
        if (names != null) {
            for (List<?> name : names) {
                if (name.size() < 2 || !(name.get(0) instanceof Integer)) continue;
                int type = (Integer) name.get(0);
                if (ipHost && type == 7) {
                    ipSanPresent = true;
                    if (ipSanMatches(name.get(1))) {
                        identityMethod = "IP subjectAltName";
                        return;
                    }
                }
                if (!ipHost && type == 2
                        && expectedHost.equalsIgnoreCase(String.valueOf(name.get(1)))) {
                    identityMethod = "DNS subjectAltName";
                    return;
                }
            }
        }
        if (ipHost && !ipSanPresent && allowIpCnFallback && commonNameMatches(leaf)) {
            identityMethod = "legacy IP commonName (IP SAN absent)";
            return;
        }
        throw new CertificateException("Server certificate does not identify the configured host");
    }

    private boolean commonNameMatches(X509Certificate leaf) throws CertificateException {
        try {
            X500Name subject = X500Name.getInstance(leaf.getSubjectX500Principal().getEncoded());
            for (RDN rdn : subject.getRDNs(BCStyle.CN)) {
                if (rdn.getFirst() != null && expectedHost.equalsIgnoreCase(
                        IETFUtils.valueToString(rdn.getFirst().getValue()))) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException e) {
            throw new CertificateException("Server certificate subject could not be parsed", e);
        }
    }

    private boolean ipSanMatches(Object value) throws CertificateException {
        try {
            byte[] expected = InetAddress.getByName(expectedHost).getAddress();
            byte[] actual = value instanceof byte[]
                    ? (byte[]) value : InetAddress.getByName(String.valueOf(value)).getAddress();
            return MessageDigest.isEqual(expected, actual);
        } catch (UnknownHostException e) {
            throw new CertificateException("Invalid IP address in server certificate", e);
        }
    }

    private static boolean isIpLiteral(String value) {
        return value.matches("[0-9.]+") || value.matches("[0-9a-fA-F:.]+");
    }
}
