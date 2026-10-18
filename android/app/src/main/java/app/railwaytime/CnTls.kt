package app.railwaytime

import android.content.Context
import android.net.http.SslCertificate
import android.net.http.SslError
import android.net.http.X509TrustManagerExtensions
import android.webkit.SslErrorHandler
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * ivrm.cn.ca leaves its intermediate certificate out of the chain; Chrome fills it in, WebView
 * doesn't. network_security_config.xml already adds that intermediate for CN sites. If WebView
 * still reports the certificate as untrusted, this completes the chain with the same bundled
 * intermediate and checks it against the phone's trusted roots and the site name — the check
 * Chrome would do. Anything else (expired, wrong name, other errors) is refused.
 */
object CnTls {
    fun handle(context: Context, error: SslError, handler: SslErrorHandler, log: (String) -> Unit): Boolean {
        val host = runCatching { URL(error.url).host }.getOrNull()
        val leaf = leafOf(error.certificate)
        val ok = error.primaryError == SslError.SSL_UNTRUSTED && host != null && leaf != null &&
            runCatching { trustedWithBundled(context, leaf, host) }.getOrDefault(false)
        if (ok) {
            log("certificate for $host completed with the bundled intermediate")
            handler.proceed()
        } else {
            log("certificate refused: error ${error.primaryError} on $host (${leaf?.issuerX500Principal?.name})")
            handler.cancel()
        }
        return ok
    }

    private fun trustedWithBundled(context: Context, leaf: X509Certificate, host: String): Boolean {
        if (!nameMatches(leaf, host)) return false
        val intermediates = context.resources.openRawResource(R.raw.sectigo_ov_r36).use {
            CertificateFactory.getInstance("X.509").generateCertificates(it).filterIsInstance<X509Certificate>()
        }
        val issuer = intermediates.firstOrNull { it.subjectX500Principal == leaf.issuerX500Principal } ?: return false
        val tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }
            .trustManagers.filterIsInstance<X509TrustManager>().first()
        val authType = if (leaf.publicKey is RSAPublicKey) "RSA" else "ECDHE_ECDSA"
        X509TrustManagerExtensions(tm).checkServerTrusted(arrayOf(leaf, issuer), authType, host)
        return true
    }

    /** The certificate WebView shows us, as a real X509 certificate (works on every Android version). */
    private fun leafOf(cert: SslCertificate?): X509Certificate? = runCatching {
        val der = SslCertificate.saveState(cert).getByteArray("x509-certificate")!!
        CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
    }.getOrNull()

    private fun nameMatches(cert: X509Certificate, host: String): Boolean {
        val names = cert.subjectAlternativeNames.orEmpty().filter { it[0] == 2 }.map { (it[1] as String).lowercase() }
        val h = host.lowercase()
        return names.any { n ->
            n == h || (n.startsWith("*.") && h.endsWith(n.substring(1)) && h.count { it == '.' } == n.count { it == '.' })
        }
    }
}
