package app.railwaytime.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Date

/**
 * ivrm.cn.ca sends only its own certificate. With the Sectigo OV R36 intermediate bundled in
 * res/raw it chains to Sectigo's public root R46 — the check the phone makes.
 */
class IvrmCertTest {
    private val cf = CertificateFactory.getInstance("X.509")
    private fun res(name: String) = cf.generateCertificate(javaClass.getResourceAsStream("/$name")) as X509Certificate
    private val bundled = File("src/main/res/raw/sectigo_ov_r36.pem").inputStream().use { cf.generateCertificate(it) as X509Certificate }
    private val leaf = res("ivrm_cn_ca.pem")
    private val root = res("sectigo_root_r46.pem")
    private val when_ = Date.from(Instant.parse("2026-10-18T22:00:00Z"))

    private fun validates(chain: List<X509Certificate>): Boolean = runCatching {
        val params = PKIXParameters(setOf(TrustAnchor(root, null))).apply { isRevocationEnabled = false; date = when_ }
        CertPathValidator.getInstance("PKIX").validate(cf.generateCertPath(chain), params)
    }.isSuccess

    @Test fun serverChainAloneFails() = assertTrue(!validates(listOf(leaf)))

    @Test fun bundledIntermediateCompletesIt() = assertTrue(validates(listOf(leaf, bundled)))

    @Test fun certificateIsForIvrm() =
        assertTrue(leaf.subjectAlternativeNames.any { it[1] == "ivrm.cn.ca" })
}
