package com.gtnhplanner.cloud;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/** A self-signed certificate made with the JDK's keytool, as a server context and a client context that trusts it. */
record TestTls(SSLContext server, SSLContext client) {

    /** {@code san} is keytool's subject alternative name list, e.g. {@code dns:localhost,ip:127.0.0.1}. */
    static TestTls make(final File dir, final String san) throws Exception {
        final File store = new File(dir, "server-" + Math.abs(san.hashCode()) + ".p12");
        final File pem = new File(dir, store.getName() + ".pem");
        final String keytool = new File(System.getProperty("java.home"), "bin/keytool").getPath();
        run(
            keytool,
            "-genkeypair",
            "-alias",
            "live",
            "-keyalg",
            "RSA",
            "-keysize",
            "2048",
            "-validity",
            "2",
            "-dname",
            "CN=live-test",
            "-ext",
            "san=" + san,
            "-storetype",
            "PKCS12",
            "-keystore",
            store.getPath(),
            "-storepass",
            "changeit");
        run(
            keytool,
            "-exportcert",
            "-rfc",
            "-alias",
            "live",
            "-keystore",
            store.getPath(),
            "-storepass",
            "changeit",
            "-file",
            pem.getPath());

        final KeyStore keys = KeyStore.getInstance("PKCS12");
        try (FileInputStream in = new FileInputStream(store)) {
            keys.load(in, "changeit".toCharArray());
        }
        final KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keys, "changeit".toCharArray());
        final SSLContext server = SSLContext.getInstance("TLS");
        server.init(kmf.getKeyManagers(), null, null);

        final KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null, null);
        try (FileInputStream in = new FileInputStream(pem)) {
            final Certificate cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(in);
            trust.setCertificateEntry("live", cert);
        }
        final TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        final SSLContext client = SSLContext.getInstance("TLS");
        client.init(null, tmf.getTrustManagers(), null);
        return new TestTls(server, client);
    }

    private static void run(final String... cmd) throws IOException, InterruptedException {
        final Process p = new ProcessBuilder(cmd).redirectErrorStream(true)
            .start();
        final String out = new String(
            p.getInputStream()
                .readAllBytes());
        if (p.waitFor() != 0) throw new IOException("keytool failed: " + out);
    }
}
