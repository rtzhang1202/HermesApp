package com.ai.assistance.operit.voice.lens

/**
 * 敏感页面拦截：银行 / 支付 / 密码管理器类包名不做任何截屏与上传。
 *
 * 调研建议一「隐私与合规」：包名黑名单拦截率要求 100%。此处用「已知包名 + 关键词启发式」双保险，
 * 命中即整页拦截（连节点树都不读，直接提示），不做「模糊处理后再识别」。
 */
object SensitiveScreenGuard {

    /** 已知银行/支付/密码管理器包名（前缀匹配） */
    private val KNOWN_PACKAGES =
            listOf(
                    // 国内银行
                    "com.icbc", "com.chinamworld.main", "com.ccb.longjiLife", "com.ccb",
                    "com.bankcomm", "cmb.pb", "com.cmbchina", "com.android.bankabc",
                    "com.ecitic.bank", "com.spdb.mobilebank", "com.pingan.paces.ccms",
                    "com.cib.cibmb", "com.cebbank.mobile", "com.cgbchina.xpt",
                    "com.bankofbeijing", "com.bankofshanghai", "com.srcb.pmbank",
                    "com.chinamworld.bocmbci", "com.psbc.mobilebank",
                    // 支付/钱包
                    "com.eg.android.alipaygphone", "com.unionpay", "com.tencent.mm.plugin",
                    "com.mipay.wallet", "com.xiaomi.wallet", "com.huawei.wallet",
                    "com.samsung.android.spay", "com.google.android.apps.walletnfcrel",
                    "com.paypal.android.p2pmobile", "com.venmo",
                    // 密码管理器
                    "com.onepassword.android", "com.bitwarden", "com.keepassdroid",
                    "com.lastpass.lpandroid", "com.dashlane", "com.x8bit.bitwarden",
                    "com.authy.authy", "com.google.android.apps.authenticator2",
                    "com.microsoft.authenticator", "com.azure.authenticator"
            )

    /** 包名关键词（兜底：覆盖未列出的银行/支付 App，宁可误拦不可漏传） */
    private val KEYWORDS =
            listOf(
                    "bank", "ebank", "mobilebank", "pay", "wallet", "unionpay",
                    "password", "passwd", "keepass", "bitwarden", "authenticator",
                    "securid", "token2fa", "creditcard"
            )

    fun blockReason(packageName: String?): String? {
        val pkg = packageName?.trim()?.lowercase().orEmpty()
        if (pkg.isEmpty()) return null
        if (KNOWN_PACKAGES.any { pkg == it || pkg.startsWith("$it.") }) {
            return "金融/支付/密码类应用（$pkg）"
        }
        val hit = KEYWORDS.firstOrNull { pkg.contains(it) }
        if (hit != null) return "金融/支付/密码类应用（$pkg）"
        return null
    }

    fun isBlocked(packageName: String?): Boolean = blockReason(packageName) != null
}
