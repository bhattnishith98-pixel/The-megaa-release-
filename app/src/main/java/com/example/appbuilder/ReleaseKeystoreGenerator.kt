package com.example.appbuilder

import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class KeystoreGenerationConfig(
    val alias: String = "upload",
    val storePassword: String = "changeit123",
    val keyPassword: String = "changeit123",
    val commonName: String = "Automation Studio Developer",
    val organization: String = "Automation Studio",
    val countryCode: String = "IN",
    val validityDays: Int = 9125 // 25 years (Google Play recommended)
)

data class GeneratedKeystoreResult(
    val keystoreBytes: ByteArray,
    val base64Encoded: String,
    val sha256Fingerprint: String,
    val sha1Fingerprint: String,
    val alias: String,
    val subjectDn: String,
    val validityDays: Int,
    val format: String = "PKCS12"
)

/**
 * Generates genuine Android release signing keystores (PKCS12, compatible with AGP signingConfigs,
 * keytool, jarsigner, apksigner, Google Play Console upload keys, and GitHub Actions KEYSTORE_BASE64).
 */
object ReleaseKeystoreGenerator {

    fun generatePkcs12Keystore(config: KeystoreGenerationConfig): GeneratedKeystoreResult {
        require(config.alias.isNotBlank()) { "Key alias cannot be blank." }
        require(config.storePassword.length >= 6) { "Keystore password must be at least 6 characters." }
        require(config.keyPassword.length >= 6) { "Key password must be at least 6 characters." }

        val keyGen = KeyPairGenerator.getInstance("RSA")
        keyGen.initialize(2048)
        val keyPair = keyGen.generateKeyPair()

        val cert = buildSelfSignedX509Certificate(
            commonName = config.commonName.ifBlank { "Studio App" },
            organization = config.organization.ifBlank { "Automation Studio" },
            country = config.countryCode.take(2).uppercase().ifBlank { "US" },
            validityDays = config.validityDays.coerceIn(365, 10000),
            publicKeyEncoded = keyPair.public.encoded,
            privateKey = keyPair.private
        )

        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry(
            config.alias.trim(),
            keyPair.private,
            config.keyPassword.toCharArray(),
            arrayOf(cert)
        )

        val baos = ByteArrayOutputStream()
        ks.store(baos, config.storePassword.toCharArray())
        val bytes = baos.toByteArray()

        val certDer = cert.encoded
        val sha256 = MessageDigest.getInstance("SHA-256").digest(certDer).joinToString(":") { "%02X".format(it) }
        val sha1 = MessageDigest.getInstance("SHA-1").digest(certDer).joinToString(":") { "%02X".format(it) }
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)

        return GeneratedKeystoreResult(
            keystoreBytes = bytes,
            base64Encoded = base64,
            sha256Fingerprint = sha256,
            sha1Fingerprint = sha1,
            alias = config.alias.trim(),
            subjectDn = cert.subjectX500Principal.name,
            validityDays = config.validityDays.coerceIn(365, 10000)
        )
    }

    private fun buildSelfSignedX509Certificate(
        commonName: String,
        organization: String,
        country: String,
        validityDays: Int,
        publicKeyEncoded: ByteArray,
        privateKey: PrivateKey
    ): X509Certificate {
        val now = Date()
        val expiry = Date(now.time + validityDays.toLong() * 24L * 60L * 60L * 1000L)

        // [0] EXPLICIT INTEGER 2 (X.509 v3)
        val versionTlv = derTag(0xA0, derInteger(BigInteger.valueOf(2)))
        val serial = BigInteger(63, java.security.SecureRandom()).abs().add(BigInteger.ONE)
        val serialTlv = derInteger(serial)
        val sigAlgId = derSha256WithRsaAlgId()

        val dnName = derSequence(
            listOf(
                derRdn("2.5.4.6", country, isPrintable = true), // C
                derRdn("2.5.4.10", organization, isPrintable = false), // O
                derRdn("2.5.4.3", commonName, isPrintable = false) // CN
            )
        )

        val validity = derSequence(listOf(derTime(now), derTime(expiry)))

        val tbsBytes = derSequence(
            listOf(
                versionTlv,
                serialTlv,
                sigAlgId,
                dnName, // issuer
                validity,
                dnName, // subject
                publicKeyEncoded // SubjectPublicKeyInfo (already complete DER SEQUENCE)
            )
        )

        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(privateKey)
        sig.update(tbsBytes)
        val signatureBytes = sig.sign()

        val bitStringSig = derBitString(signatureBytes)
        val certDer = derSequence(listOf(tbsBytes, sigAlgId, bitStringSig))

        val cf = CertificateFactory.getInstance("X.509")
        return cf.generateCertificate(ByteArrayInputStream(certDer)) as X509Certificate
    }

    private fun derSha256WithRsaAlgId(): ByteArray {
        // OID 1.2.840.113549.1.1.11 + NULL
        val oid = byteArrayOf(0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B)
        val nullParam = byteArrayOf(0x05, 0x00)
        return derSequence(listOf(oid, nullParam))
    }

    private fun derRdn(oidStr: String, value: String, isPrintable: Boolean): ByteArray {
        val oid = derOid(oidStr)
        val valBytes = value.toByteArray(Charsets.UTF_8)
        val strTag = if (isPrintable) 0x13 else 0x0C // PrintableString or UTF8String
        val strTlv = derTag(strTag, valBytes)
        val atv = derSequence(listOf(oid, strTlv))
        return derTag(0x31, atv) // SET
    }

    private fun derOid(oid: String): ByteArray {
        val parts = oid.split(".").map { it.toInt() }
        val baos = ByteArrayOutputStream()
        baos.write(parts[0] * 40 + parts[1])
        for (i in 2 until parts.size) {
            var v = parts[i]
            val enc = mutableListOf<Int>()
            enc.add(v and 0x7F)
            v = v ushr 7
            while (v > 0) {
                enc.add((v and 0x7F) or 0x80)
                v = v ushr 7
            }
            enc.reversed().forEach { baos.write(it) }
        }
        return derTag(0x06, baos.toByteArray())
    }

    private fun derTime(date: Date): ByteArray {
        val cal = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { time = date }
        val year = cal.get(java.util.Calendar.YEAR)
        return if (year in 1950..2049) {
            val fmt = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            derTag(0x17, fmt.format(date).toByteArray(Charsets.US_ASCII)) // UTCTime
        } else {
            val fmt = SimpleDateFormat("yyyyMMddHHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            derTag(0x18, fmt.format(date).toByteArray(Charsets.US_ASCII)) // GeneralizedTime
        }
    }

    private fun derInteger(value: BigInteger): ByteArray = derTag(0x02, value.toByteArray())

    private fun derBitString(bytes: ByteArray): ByteArray {
        val payload = ByteArray(bytes.size + 1)
        payload[0] = 0x00 // 0 unused bits
        System.arraycopy(bytes, 0, payload, 1, bytes.size)
        return derTag(0x03, payload)
    }

    private fun derSequence(items: List<ByteArray>): ByteArray {
        val baos = ByteArrayOutputStream()
        items.forEach { baos.write(it) }
        return derTag(0x30, baos.toByteArray())
    }

    private fun derTag(tag: Int, content: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        baos.write(tag)
        val len = content.size
        when {
            len < 128 -> baos.write(len)
            len <= 0xFF -> { baos.write(0x81); baos.write(len) }
            len <= 0xFFFF -> { baos.write(0x82); baos.write((len shr 8) and 0xFF); baos.write(len and 0xFF) }
            else -> {
                baos.write(0x83)
                baos.write((len shr 16) and 0xFF)
                baos.write((len shr 8) and 0xFF)
                baos.write(len and 0xFF)
            }
        }
        baos.write(content)
        return baos.toByteArray()
    }
}
