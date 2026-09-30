// @author 雾晚
package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import io.nekohasekai.sagernet.utils.SecureNetwork
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONObject
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import androidx.appcompat.widget.AppCompatSpinner
import androidx.appcompat.widget.Toolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.LayoutWebviewBinding
import io.nekohasekai.sagernet.ktx.Logs
import moe.matsuri.nb4a.utils.WebViewUtil

// Fragment必须有一个无参public的构造函数，否则在数据恢复的时候，会报crash

class WebviewFragment : ToolbarFragment(R.layout.layout_webview), Toolbar.OnMenuItemClickListener {

    private val dashboardClient = SecureNetwork.webDAVClient()
    lateinit var mWebView: WebView

    companion object {
        const val DEFAULT_YACD_URL = "http://127.0.0.1:9090/ui"
        const val PRESET_ZASHBOARD_URL = "https://board.zash.run.place/"
    }

    private fun updateToolbarSubtitle() {
        val currentUrl = DataStore.yacdURL
        val subtitle = when {
            currentUrl.contains("zash.run.place") -> "Zashboard"
            currentUrl == DEFAULT_YACD_URL || currentUrl.contains("127.0.0.1:9090") -> "YACD"
            else -> "Custom"
        }
        toolbar.subtitle = subtitle
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 规范化旧版本遗留的 setup 路由，使其直接访问根路径
        if (DataStore.yacdURL.startsWith("https://board.zash.run.place/#/setup")) {
            DataStore.yacdURL = PRESET_ZASHBOARD_URL
        }

        // layout
        toolbar.setTitle(R.string.menu_dashboard)
        updateToolbarSubtitle()
        toolbar.inflateMenu(R.menu.yacd_menu)
        toolbar.setOnMenuItemClickListener(this)

        val binding = LayoutWebviewBinding.bind(view)

        // webview
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        mWebView = binding.webview
        mWebView.settings.apply {
            domStorageEnabled = true
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            // HTTPS 外部面板不能访问本地明文 API；内置面板使用同源认证。
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        mWebView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val target = request?.url?.toString()?.toHttpUrlOrNull() ?: return true
                return !target.isHttps && !(target.host == "127.0.0.1" && target.port == 9090)
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString()?.toHttpUrlOrNull() ?: return null
                if (url.host != "127.0.0.1" || url.port != 9090 || url.scheme != "http" ||
                    url.encodedPath !in setOf("/ui", "/ui/", "/ui/index.html") || request.method != "GET") return null
                return try {
                    dashboardClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                        if (!response.isSuccessful) return null
                        val html = SecureNetwork.text(response.body)
                        // Seed the bundled YACD before its JS starts. Secret stays on this local origin;
                        // it is never sent to a remote dashboard or an application log.
                        val secret = JSONObject.quote(DataStore.clashApiSecret)
                        val bootstrap = """
                            <script>(function(){var k="yacd.metacubex.one",s={};
                            try{s=JSON.parse(localStorage.getItem(k)||"{}")}catch(e){}
                            s.clashAPIConfigs=[{baseURL:"http://127.0.0.1:9090",secret:$secret,addedAt:0}];
                            s.selectedClashAPIConfigIndex=0;localStorage.setItem(k,JSON.stringify(s));})();</script>
                        """.trimIndent()
                        val document = html.replaceFirst("<head>", "<head>" + bootstrap)
                        WebResourceResponse("text/html", "UTF-8", document.byteInputStream())
                    }
                } catch (e: Exception) {
                    null
                }
            }

            override fun onReceivedError(
                view: WebView?, request: WebResourceRequest?, error: WebResourceError?
            ) {
                WebViewUtil.onReceivedError(view, request, error)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url != null && url.contains("zash.run.place")) {
                    injectZashboardAutoConnect(view)
                }
            }
        }
        mWebView.webChromeClient = WebChromeClient()

        loadDashboard(DataStore.yacdURL)

        if (!DataStore.serviceState.connected) {
            Snackbar.make(view, "提示：请先连接代理服务以获取实时仪表盘数据", Snackbar.LENGTH_LONG).show()
        }
    }

    private fun injectZashboardAutoConnect(view: WebView?) {
        val js = """
            (function() {
                var checkCount = 0;
                var maxChecks = 30;
                var checkInterval = setInterval(function() {
                    checkCount++;
                    if (checkCount > maxChecks) {
                        clearInterval(checkInterval);
                        return;
                    }
                    if (window.location.hash.indexOf('setup') !== -1) {
                        var bodyText = document.body ? document.body.innerText : '';
                        if (bodyText.indexOf('连接正常') !== -1) {
                            var alerts = document.querySelectorAll('.el-notification, .el-message, [role="alert"], div[class*="toast"], div[class*="alert"]');
                            alerts.forEach(function(el) {
                                if (el.innerText && el.innerText.indexOf('后端连不上') !== -1) {
                                    el.style.display = 'none';
                                }
                            });
                            var buttons = Array.from(document.querySelectorAll('button'));
                            var submitBtn = buttons.find(function(b) {
                                return b.textContent && b.textContent.trim() === '提交';
                            });
                            if (submitBtn && !submitBtn.disabled) {
                                clearInterval(checkInterval);
                                submitBtn.click();
                            }
                        }
                    } else {
                        clearInterval(checkInterval);
                    }
                }, 200);
            })();
        """.trimIndent()
        view?.evaluateJavascript(js, null)
    }

    private fun loadDashboard(url: String) {
        val targetUrl = when {
            url.startsWith("https://board.zash.run.place/#/setup") -> PRESET_ZASHBOARD_URL
            else -> url
        }
        val parsed = targetUrl.toHttpUrlOrNull()
        if (parsed == null || (!parsed.isHttps && !(parsed.host == "127.0.0.1" && parsed.port == 9090))) {
            this.view?.let { Snackbar.make(it, R.string.dashboard_secure_url, Snackbar.LENGTH_LONG).show() }
            return
        }
        mWebView.loadUrl(targetUrl)
        updateToolbarSubtitle()
    }

    override fun onBackPressed(): Boolean {
        if (::mWebView.isInitialized && mWebView.canGoBack()) {
            mWebView.goBack()
            return true
        }
        return false
    }

    override fun onDestroyView() {
        if (::mWebView.isInitialized) {
            try {
                mWebView.stopLoading()
                mWebView.loadUrl("about:blank")
                mWebView.clearHistory()
                (mWebView.parent as? ViewGroup)?.removeView(mWebView)
                mWebView.destroy()
            } catch (e: Exception) {
                Logs.w("Failed to destroy WebView: ${e.message}")
            }
        }
        dashboardClient.dispatcher.cancelAll()
        dashboardClient.connectionPool.evictAll()
        super.onDestroyView()
    }

    @SuppressLint("CheckResult")
    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_refresh -> {
                if (::mWebView.isInitialized) {
                    mWebView.reload()
                    view?.let { Snackbar.make(it, R.string.action_refresh, Snackbar.LENGTH_SHORT).show() }
                }
            }
            R.id.action_set_url -> {
                showDashboardPresetDialog()
            }
            R.id.close -> {
                (activity as? MainActivity)?.displayFragmentWithId(R.id.nav_configuration)
            }
        }
        return true
    }

    private fun showDashboardPresetDialog() {
        val dialogContext = requireContext()
        val view = LayoutInflater.from(dialogContext).inflate(R.layout.layout_dialog_dashboard_url, null)
        val spinner = view.findViewById<AppCompatSpinner>(R.id.spinner_dashboard_presets)
        val editUrl = view.findViewById<EditText>(R.id.edit_dashboard_url)

        val presetNames = listOf(
            getString(R.string.dashboard_preset_zashboard),
            getString(R.string.dashboard_preset_yacd),
            getString(R.string.dashboard_preset_custom)
        )
        val presetUrls = listOf(
            PRESET_ZASHBOARD_URL,
            DEFAULT_YACD_URL,
            ""
        )

        val currentUrl = DataStore.yacdURL
        editUrl.setText(currentUrl)
        editUrl.setSelection(currentUrl.length)

        val adapter = ArrayAdapter(dialogContext, android.R.layout.simple_spinner_dropdown_item, presetNames)
        spinner.adapter = adapter

        val initialIndex = when {
            currentUrl.contains("zash.run.place") -> 0
            currentUrl == DEFAULT_YACD_URL || currentUrl.contains("127.0.0.1:9090") -> 1
            else -> 2
        }
        spinner.setSelection(initialIndex)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            private var isFirst = true
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isFirst) {
                    isFirst = false
                    return
                }
                if (position in 0..1) {
                    val chosenUrl = presetUrls[position]
                    editUrl.setText(chosenUrl)
                    editUrl.setSelection(chosenUrl.length)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        MaterialAlertDialogBuilder(dialogContext)
            .setTitle(R.string.set_panel_url)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val enteredUrl = editUrl.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
                    ?: DEFAULT_YACD_URL
                DataStore.yacdURL = enteredUrl
                loadDashboard(enteredUrl)
                this.view?.let { Snackbar.make(it, R.string.dashboard_switched_toast, Snackbar.LENGTH_SHORT).show() }
            }
            .setNeutralButton(R.string.dashboard_reset_default) { _, _ ->
                DataStore.yacdURL = DEFAULT_YACD_URL
                loadDashboard(DEFAULT_YACD_URL)
                this.view?.let { Snackbar.make(it, R.string.dashboard_switched_toast, Snackbar.LENGTH_SHORT).show() }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
