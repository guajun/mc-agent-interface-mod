package dev.mcagent.interfacemod.control;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Server-side TLS identity for the formal control protocol (issue #8).
 *
 * <p>There is no certificate shipped in the jar: every instance owns its own
 * identity, generated on first start. The generator is the JDK's own
 * {@code keytool} (EC P-256, self-signed, SAN for the loopback names and the
 * machine's addresses), loaded back as a PKCS#12 keystore. When {@code keytool}
 * is not next to the running JVM the operator can point the mod at a PKCS#12
 * keystore or a PEM key/certificate pair instead; the protocol stays off (with
 * a clear log line) rather than falling back to an unauthenticated endpoint.
 *
 * <p>The public certificate is written to {@code server.crt} and its SHA-256
 * fingerprint to {@code fingerprint.txt} so a daemon can pin the exact
 * identity out of band. The private key never leaves the control directory;
 * {@code store.pass} next to it is sensitive local data and is created with
 * restrictive permissions where the filesystem supports it.
 */
public final class ControlTls {
    public static final String PROPERTY_KEYSTORE = "mcagent.controlKeystore";
    public static final String PROPERTY_STORE_PASSWORD = "mcagent.controlStorePassword";
    public static final String PROPERTY_KEY_ALIAS = "mcagent.controlKeyAlias";
    public static final String PROPERTY_CERTIFICATE = "mcagent.controlCertificate";
    public static final String PROPERTY_PRIVATE_KEY = "mcagent.controlPrivateKey";
    public static final String PROPERTY_HOST = "mcagent.controlHost";

    public static final String DEFAULT_ALIAS = "mcagent-control";
    public static final String KEYSTORE_FILE = "control.p12";
    public static final String PASSWORD_FILE = "store.pass";
    public static final String CERTIFICATE_FILE = "server.crt";
    public static final String FINGERPRINT_FILE = "fingerprint.txt";

    private ControlTls() {
    }

    /** Everything the Netty TLS server and the operator need. */
    public static final class Material {
        public final PrivateKey privateKey;
        public final X509Certificate[] chain;
        public final String fingerprint;

        Material(PrivateKey privateKey, X509Certificate[] chain, String fingerprint) {
            this.privateKey = privateKey;
            this.chain = chain;
            this.fingerprint = fingerprint;
        }

        public SslContext sslContext() throws Exception {
            return SslContextBuilder.forServer(privateKey, chain)
                    .protocols("TLSv1.3", "TLSv1.2")
                    .build();
        }
    }

    /** Load or create the identity under {@code directory}. */
    public static Material prepare(Path directory) throws Exception {
        Files.createDirectories(directory);

        String keystoreProperty = System.getProperty(PROPERTY_KEYSTORE);
        String certificateProperty = System.getProperty(PROPERTY_CERTIFICATE);
        String privateKeyProperty = System.getProperty(PROPERTY_PRIVATE_KEY);

        Material material;
        if (keystoreProperty != null && !keystoreProperty.isBlank()) {
            String password = System.getProperty(PROPERTY_STORE_PASSWORD);
            if (password == null || password.isEmpty()) {
                throw new IOException("-D" + PROPERTY_KEYSTORE
                        + " needs -D" + PROPERTY_STORE_PASSWORD + " with the PKCS#12 password");
            }
            material = loadKeystore(Path.of(keystoreProperty), password,
                    System.getProperty(PROPERTY_KEY_ALIAS, DEFAULT_ALIAS));
        } else if (certificateProperty != null && !certificateProperty.isBlank()
                && privateKeyProperty != null && !privateKeyProperty.isBlank()) {
            material = loadPem(Path.of(certificateProperty), Path.of(privateKeyProperty));
        } else {
            Path keystore = directory.resolve(KEYSTORE_FILE);
            Path passwordFile = directory.resolve(PASSWORD_FILE);
            if (!Files.isRegularFile(keystore)) {
                generateWithKeytool(keystore, passwordFile, directory);
            }
            if (!Files.isRegularFile(passwordFile)) {
                throw new IOException("missing " + passwordFile
                        + "; delete " + keystore + " together with it to regenerate the identity");
            }
            String password = Files.readString(passwordFile, StandardCharsets.UTF_8).trim();
            material = loadKeystore(keystore, password, DEFAULT_ALIAS);
        }

        writePublicIdentity(directory, material);
        return material;
    }

    private static Material loadKeystore(Path keystore, String password, String alias) throws Exception {
        if (!Files.isRegularFile(keystore)) {
            throw new IOException("control keystore not found: " + keystore);
        }
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(keystore)) {
            store.load(input, password.toCharArray());
        }
        String chosen = alias;
        if (!store.containsAlias(chosen)) {
            chosen = null;
            var aliases = store.aliases();
            while (aliases.hasMoreElements()) {
                String candidate = aliases.nextElement();
                if (store.isKeyEntry(candidate)) {
                    chosen = candidate;
                    break;
                }
            }
        }
        if (chosen == null) {
            throw new IOException("control keystore has no private key entry: " + keystore);
        }
        PrivateKey key = (PrivateKey) store.getKey(chosen, password.toCharArray());
        java.security.cert.Certificate certificate = store.getCertificate(chosen);
        if (key == null || !(certificate instanceof X509Certificate x509)) {
            throw new IOException("control keystore entry is not an X.509 key pair: " + keystore);
        }
        return new Material(key, new X509Certificate[] {x509}, fingerprint(x509));
    }

    private static Material loadPem(Path certificatePath, Path privateKeyPath) throws Exception {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        X509Certificate certificate;
        try (var input = Files.newInputStream(certificatePath)) {
            certificate = (X509Certificate) factory.generateCertificate(input);
        }
        byte[] der = readPem(privateKeyPath, "PRIVATE KEY");
        KeyFactory keyFactory = KeyFactory.getInstance(certificate.getPublicKey().getAlgorithm());
        PrivateKey key = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(der));
        return new Material(key, new X509Certificate[] {certificate}, fingerprint(certificate));
    }

    private static void generateWithKeytool(Path keystore, Path passwordFile, Path directory)
            throws Exception {
        String javaHome = System.getProperty("java.home");
        String executable = javaHome + File.separator + "bin" + File.separator + "keytool"
                + (isWindows() ? ".exe" : "");
        if (!Files.isRegularFile(Path.of(executable))) {
            throw new IOException("keytool not found at " + executable
                    + "; run the server on a JDK, or pass -D" + PROPERTY_KEYSTORE + " + -D"
                    + PROPERTY_STORE_PASSWORD + " (PKCS#12), or -D" + PROPERTY_CERTIFICATE
                    + " + -D" + PROPERTY_PRIVATE_KEY + " (PEM)");
        }
        String password = randomPassword();
        String sans = String.join(",", subjectAlternativeNames());
        String environment = "MCAGENT_CONTROL_STORE_PASS";
        ProcessBuilder builder = new ProcessBuilder(executable,
                "-genkeypair",
                "-alias", DEFAULT_ALIAS,
                "-keyalg", "EC",
                "-groupname", "secp256r1",
                "-sigalg", "SHA256withECDSA",
                "-dname", "CN=mc-agent-control",
                "-ext", "SAN=" + sans,
                "-validity", "3650",
                "-keystore", keystore.toAbsolutePath().toString(),
                "-storetype", "PKCS12",
                "-storepass:env", environment,
                "-keypass:env", environment,
                "-noprompt");
        builder.environment().put(environment, password);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = process.waitFor();
        if (code != 0 || !Files.isRegularFile(keystore)) {
            throw new IOException("keytool failed to create the control identity (exit " + code + "): "
                    + output.trim());
        }
        Files.writeString(passwordFile, password + System.lineSeparator(), StandardCharsets.UTF_8);
        restrict(passwordFile);
        System.out.println("[mc-agent-interface] generated control TLS identity in "
                + directory.toAbsolutePath() + " (SAN " + sans + ")");
    }

    private static void writePublicIdentity(Path directory, Material material) throws IOException {
        Path certificate = directory.resolve(CERTIFICATE_FILE);
        Path fingerprint = directory.resolve(FINGERPRINT_FILE);
            String certificatePem;
            try {
                certificatePem = pem("CERTIFICATE", material.chain[0].getEncoded());
            } catch (java.security.cert.CertificateEncodingException exception) {
                throw new IOException("cannot encode the control certificate", exception);
            }
            Files.writeString(certificate, certificatePem,
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.writeString(fingerprint, material.fingerprint + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    /** SAN entries: loopback is always present; the machine addresses are best effort. */
    static List<String> subjectAlternativeNames() {
        Set<String> names = new LinkedHashSet<>();
        names.add("dns:localhost");
        names.add("ip:127.0.0.1");
        String configured = System.getProperty(PROPERTY_HOST);
        if (configured != null && !configured.isBlank()) {
            names.add("dns:" + configured.trim());
        }
        try {
            String hostname = java.net.InetAddress.getLocalHost().getHostName();
            if (hostname != null && !hostname.isBlank() && hostname.length() <= 253) {
                names.add("dns:" + hostname);
            }
        } catch (IOException ignored) {
            // best effort only
        }
        try {
            var interfaces = java.net.NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                for (java.net.NetworkInterface nic : java.util.Collections.list(interfaces)) {
                    for (java.net.InetAddress inet : java.util.Collections.list(nic.getInetAddresses())) {
                        String literal = inet.getHostAddress();
                        if (literal == null || literal.contains("%")) {
                            continue;
                        }
                        names.add("ip:" + literal);
                    }
                }
            }
        } catch (Exception ignored) {
            // address enumeration is optional
        }
        List<String> result = new ArrayList<>(names);
        // keytool wants a bounded, single-line argument; drop duplicates and cap it.
        if (result.size() > 32) {
            result = result.subList(0, 32);
        }
        return result;
    }

    public static String fingerprint(X509Certificate certificate) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] value = digest.digest(certificate.getEncoded());
        StringBuilder builder = new StringBuilder("sha256:");
        for (byte b : value) {
            builder.append(String.format(Locale.ROOT, "%02x", b));
        }
        return builder.toString();
    }

    static String pem(String type, byte[] der) {
        String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
    }

    static byte[] readPem(Path path, String type) throws IOException {
        String text = Files.readString(path, StandardCharsets.UTF_8);
        String begin = "-----BEGIN " + type + "-----";
        String end = "-----END " + type + "-----";
        int start = text.indexOf(begin);
        int stop = text.indexOf(end);
        if (start < 0 || stop < 0 || stop <= start) {
            throw new IOException("no " + type + " PEM block in " + path);
        }
        String body = text.substring(start + begin.length(), stop).replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }

    private static String randomPassword() {
        byte[] value = new byte[24];
        new SecureRandom().nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static void restrict(Path path) {
        try {
            Set<java.nio.file.attribute.PosixFilePermission> permissions =
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows and exotic filesystems: the user profile ACL is the boundary.
        }
    }
}
