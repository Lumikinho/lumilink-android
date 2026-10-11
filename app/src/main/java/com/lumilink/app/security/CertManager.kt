package com.lumilink.app.security

import android.content.Context
import android.util.Log
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.security.auth.x500.X500Principal

// Certificado autoassinado próprio (BouncyCastle), guardado em PKCS12.
class CertManager(ctx: Context, private val log: (String) -> Unit) {
    private val appCtx = ctx.applicationContext
    private val file = File(appCtx.filesDir, "node.p12")
    private val pass: CharArray by lazy {
        val sp = appCtx.getSharedPreferences("sec", Context.MODE_PRIVATE)
        sp.getString("ks_pass", null)?.toCharArray() ?: run {
            val p = BigInteger(96, SecureRandom()).toString(32).toCharArray()
            sp.edit().putString("ks_pass", String(p)).apply()
            p
        }
    }

    val cert: X509Certificate
    val fingerprint: String
    private val ks: KeyStore

    init {
        if (Security.getProvider("BC") == null) Security.addProvider(BouncyCastleProvider())
        ks = KeyStore.getInstance("PKCS12")
        if (file.exists()) {
            file.inputStream().use { ks.load(it, pass) }
        } else {
            ks.load(null, pass)
            generate()
            file.outputStream().use { ks.store(it, pass) }
            log("[tls] certificado autoassinado gerado.")
        }
        cert = ks.getCertificate("node") as X509Certificate
        fingerprint = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString("") { "%02x".format(it) }
    }

    private fun generate() {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp = kpg.generateKeyPair()
        val now = Date()
        val cal = Calendar.getInstance().apply { time = now; add(Calendar.YEAR, 5) }
        val princ = X500Principal("CN=LumiLink")
        val builder = JcaX509v3CertificateBuilder(princ,
            BigInteger(64, SecureRandom()), now, cal.time, princ, kp.public)
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(kp.private)
        val holder = builder.build(signer)
        ks.setKeyEntry("node", kp.private, pass,
            arrayOf(JcaX509CertificateConverter().getCertificate(holder)))
    }

    fun serverSslContext(): SSLContext {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, pass)
        return SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, arrayOf(BouncyCastleTrustAll), SecureRandom())
        }
    }

    private object BouncyCastleTrustAll : javax.net.ssl.X509TrustManager {
        override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
        override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }
}
