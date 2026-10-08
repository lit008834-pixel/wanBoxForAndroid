// @author 雾晚
package io.nekohasekai.sagernet.widget

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.whenStarted
import com.google.android.material.bottomappbar.BottomAppBar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.*
import android.app.Activity
import io.nekohasekai.sagernet.ui.UiChrome
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.utils.LandingIpManager
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StatsBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
    defStyleAttr: Int = R.attr.bottomAppBarStyle,
) : BottomAppBar(context, attrs, defStyleAttr) {
    companion object {
        private const val INITIAL_HIDE_DELAY_MS = 100L
        private const val SCROLL_TOGGLE_THRESHOLD_DP = 8f
    }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private fun runOnUi(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private enum class Transition {
        ShowImmediate,
        ShowAnimated,
        HideImmediate,
        HideAfterStart,
    }

    private lateinit var statusText: TextView
    private lateinit var statusTitleText: TextView
    private lateinit var statusIpText: TextView
    private lateinit var txText: TextView
    private lateinit var rxText: TextView
    private var btnIpDetail: View? = null
    @Suppress("unused")
    private lateinit var behavior: YourBehavior
    private var currentState = BaseService.State.Idle
    private var pendingTransition: Transition? = Transition.HideImmediate
    private var transitionJob: Job? = null

    private var scrollHidden = false
    private var scrollDirection = 0 // 1 = hide (dy>0), -1 = show (dy<0), 0 = none
    private var scrollAccumulatedDy = 0
    private val scrollToggleThresholdPx =
        (SCROLL_TOGGLE_THRESHOLD_DP * resources.displayMetrics.density).toInt().coerceAtLeast(8)

    var useExternalScrollDriver = false
        set(value) {
            if (field == value) return
            field = value
            syncScrollHiddenFromView()
            resetScrollDriverState()
            updateHideOnScroll()
        }

    var allowShow = false
        set(value) {
            field = value
            updateHideOnScroll()
        }

    init {
        alpha = 0f
    }

    override fun getBehavior(): YourBehavior {
        if (!this::behavior.isInitialized) behavior = YourBehavior()
        return behavior
    }

    inner class YourBehavior : Behavior() {

        override fun onStartNestedScroll(
            coordinatorLayout: CoordinatorLayout,
            child: BottomAppBar,
            directTarget: View,
            target: View,
            nestedScrollAxes: Int,
            type: Int,
        ): Boolean {
            if (useExternalScrollDriver) return false
            return super.onStartNestedScroll(
                coordinatorLayout,
                child,
                directTarget,
                target,
                nestedScrollAxes,
                type,
            )
        }

        override fun slideUp(child: BottomAppBar) {
            if (!allowShow) return
            super.slideUp(child)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val transition = pendingTransition
        if (transition != null) {
            pendingTransition = null
            applyTransition(transition)
        } else if (alpha == 0f) {
            alpha = 1f
        }
    }

    private fun initViews() {
        if (!this::statusText.isInitialized) {
            statusText = findViewById(R.id.status)
            statusText.isSelected = true
            statusTitleText = findViewById(R.id.status_title)
            statusTitleText.isSelected = true
            statusIpText = findViewById(R.id.status_ip)
            statusIpText.isSelected = true
            statusIpText.setOnClickListener {
                onIpDetailClicked()
            }
            txText = findViewById(R.id.tx)
            rxText = findViewById(R.id.rx)
            btnIpDetail = findViewById(R.id.btn_ip_detail)
            btnIpDetail?.setOnClickListener {
                onIpDetailClicked()
            }
            btnIpDetail?.setOnLongClickListener {
                val cached = LandingIpManager.getCachedInfo()
                val activity = context as? Activity
                if (cached != null && activity != null) {
                    LandingIpBottomSheet.show(activity, cached) {
                        refreshLandingIp(forceRefresh = true)
                        retestLatencyInPlace()
                    }
                    true
                } else {
                    false
                }
            }
            updateThemeColors()
        }
    }

    // @author 雾晚: visual tint only; the probe identity, cancellation and result state stay intact.
    fun updateThemeColors() {
        if (!this::statusText.isInitialized) return
        backgroundTintList = UiChrome.tint(context)
        val primary = UiChrome.text(context, secondary = false)
        val secondary = UiChrome.text(context, secondary = true)
        txText.setTextColor(secondary)
        rxText.setTextColor(secondary)
        statusIpText.setTextColor(primary)
        statusTitleText.setTextColor(secondary)
        statusText.setTextColor(primary)
        (btnIpDetail as? ImageView)?.imageTintList = ColorStateList.valueOf(primary)
    }

    private var latencyAttached = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        latencyAttached = true
        initViews()
        updateThemeColors()
        // @author 雾晚: reopening only renders state; latency probes require a user action.
        updateStatusViews()
    }

    fun onIpDetailClicked() {
        // 就地静默刷新：点击按钮时立刻通过当前实际出口重新发起握手与 IP 查询，绝不弹窗
        (btnIpDetail as? android.widget.ImageView)?.apply {
            animate().rotationBy(360f).setDuration(400).start()
        }
        if (DataStore.showLandingIp) {
            statusIpText.text = context.getString(R.string.landing_ip_querying)
            statusIpText.visibility = View.VISIBLE
        }
        refreshLandingIp(forceRefresh = true)
        retestLatencyInPlace()
    }

    fun retestLatencyInPlace() {
        testConnection(silent = false)
    }

    override fun setOnClickListener(l: OnClickListener?) {
        initViews()
        super.setOnClickListener(l)
    }

    private fun setStatus(text: CharSequence) {
        initViews()
        statusText.text = text
        TooltipCompat.setTooltipText(this, text)
    }

    private fun updateHideOnScroll() {
        hideOnScroll =
            !useExternalScrollDriver && allowShow && currentState == BaseService.State.Connected
    }

    private fun shouldShow(): Boolean {
        return allowShow && currentState == BaseService.State.Connected
    }

    private fun resetScrollDriverState() {
        scrollDirection = 0
        scrollAccumulatedDy = 0
    }

    private fun syncScrollHiddenFromView() {
        if (!isLaidOut || height <= 0) return
        scrollHidden = translationY >= height / 2f
    }

    fun onListScrolled(dy: Int) {
        if (!useExternalScrollDriver || !shouldShow() || dy == 0) return

        val direction = if (dy > 0) 1 else -1
        if (direction != scrollDirection) {
            scrollDirection = direction
            scrollAccumulatedDy = 0
        }
        scrollAccumulatedDy += dy

        val wantHidden = scrollAccumulatedDy > 0
        if (wantHidden == scrollHidden) {
            if (abs(scrollAccumulatedDy) > scrollToggleThresholdPx) {
                scrollAccumulatedDy = direction * scrollToggleThresholdPx
            }
            return
        }
        if (abs(scrollAccumulatedDy) < scrollToggleThresholdPx) return

        scrollHidden = wantHidden
        scrollAccumulatedDy = 0
        if (wantHidden) performHide() else performShow()
    }

    fun syncMainControls(
        showControls: Boolean,
        state: BaseService.State,
        showWhenConnected: Boolean,
        animate: Boolean,
    ) {
        runOnUi {
            currentState = state
            allowShow = showControls
            when {
                !showControls || state != BaseService.State.Connected -> {
                    applyTransition(
                        if (animate && showControls) Transition.HideAfterStart else Transition.HideImmediate
                    )
                }

                showWhenConnected -> applyTransition(
                    if (animate) Transition.ShowAnimated else Transition.ShowImmediate
                )
                alpha == 0f && isLaidOut -> alpha = 1f
            }
        }
    }

    private fun applyTransition(transition: Transition) {
        if (transition == Transition.HideImmediate && hasPendingDelayedHide()) return
        cancelPendingTransition()
        if (!isLaidOut || height == 0) {
            pendingTransition = transition
            alpha = if (transition == Transition.HideImmediate && !allowShow) 0f else 1f
            return
        }
        pendingTransition = null
        when (transition) {
            Transition.ShowImmediate -> commitVisible(animated = false)
            Transition.ShowAnimated -> commitVisible(animated = true)
            Transition.HideImmediate -> commitHidden(animated = false)
            Transition.HideAfterStart -> commitHidden(animated = true)
        }
    }

    private fun commitVisible(animated: Boolean) {
        alpha = 1f
        scrollHidden = false
        resetScrollDriverState()
        if (animated) {
            performShow()
        } else {
            getBehavior().slideUp(this)
            animate().cancel()
            translationY = 0f
        }
    }

    private fun commitHidden(animated: Boolean) {
        alpha = 1f
        scrollHidden = true
        resetScrollDriverState()
        if (!animated) {
            syncHiddenPosition()
            return
        }
        val activity = context as? MainActivity
        if (activity == null) {
            post {
                if (shouldShow()) {
                    commitVisible(animated = false)
                } else if (isLaidOut && height > 0) {
                    performHide()
                } else {
                    pendingTransition = Transition.HideAfterStart
                }
            }
            return
        }
        transitionJob = activity.lifecycleScope.launch(Dispatchers.Main) {
            delay(INITIAL_HIDE_DELAY_MS)
            activity.whenStarted {
                transitionJob = null
                if (shouldShow()) {
                    commitVisible(animated = false)
                } else if (isLaidOut && height > 0) {
                    performHide()
                } else {
                    pendingTransition = Transition.HideAfterStart
                }
            }
        }
    }

    private fun syncHiddenPosition() {
        getBehavior().slideDown(this)
        animate().cancel()
        translationY = height.toFloat()
        alpha = 1f
    }

    private fun cancelPendingTransition() {
        transitionJob?.cancel()
        transitionJob = null
    }

    private fun hasPendingDelayedHide(): Boolean {
        return pendingTransition == Transition.HideAfterStart || transitionJob != null
    }

    private val latencyState = LatencyProbeState()
    private val lastMeasuredLatency get() = latencyState.latency
    private var activeLatencyJob: Job? = null
    private var landingIpJob: Job? = null

    // @author 雾晚: release foreground requests; resume binds fresh module state without probing latency.
    fun onHostStopped() {
        landingIpJob?.cancel()
        landingIpJob = null
        resetLatencyState()
        LandingIpManager.clearCache()
    }

    private data class ProbeKey(
        val service: Any, val mode: String, val profile: Long, val url: String, val timeout: Int
    )

    private fun probeKey(): ProbeKey? {
        val activity = context as? MainActivity ?: return null
        if (!latencyAttached || !isAttachedToWindow || activity.lifecycle.currentState == androidx.lifecycle.Lifecycle.State.DESTROYED ||
            !DataStore.serviceState.connected ||
            currentState != BaseService.State.Connected) return null
        val service = activity.connection.service ?: return null
        return ProbeKey(service, DataStore.serviceMode, DataStore.selectedProxy,
            DataStore.connectionTestURL, DataStore.connectionTestTimeout)
    }

    private fun resetLatencyState() {
        latencyState.invalidate()
        activeLatencyJob?.cancel()
    }

    override fun onDetachedFromWindow() {
        latencyAttached = false
        onHostStopped()
        resetLatencyState()
        super.onDetachedFromWindow()
    }

    private fun formatStatus(latency: Int = lastMeasuredLatency): String {
        val isHttps = DataStore.connectionTestURL.startsWith("https://")
        val handshakeType = if (isHttps) "HTTPS" else "HTTP"
        return if (latency > 0) "$handshakeType 握手 ${latency}ms" else app.getString(R.string.vpn_connected)
    }

    private fun updateStatusViews(latency: Int? = null, customStatus: CharSequence? = null) {
        runOnUi {
            initViews()
            if (latencyState.synchronize(probeKey())) activeLatencyJob?.cancel()
            val displayedLatency = latency ?: lastMeasuredLatency
            if (currentState == BaseService.State.Connected) {
                val cached = LandingIpManager.getCachedInfo()
                if (DataStore.showLandingIp && cached != null && cached.ip.isNotBlank()) {
                    statusIpText.text = "${cached.countryFlag} ${cached.countryCode} ${cached.ip}"
                    statusIpText.visibility = View.VISIBLE
                } else if (DataStore.showLandingIp && LandingIpManager.isCurrentlyQuerying()) {
                    statusIpText.text = context.getString(R.string.landing_ip_querying)
                    statusIpText.visibility = View.VISIBLE
                } else if (DataStore.showLandingIp) {
                    statusIpText.text = LandingIpManager.getProfileFallbackDisplay(DataStore.selectedProxy)
                    statusIpText.visibility = View.VISIBLE
                } else {
                    statusIpText.visibility = View.GONE
                }

                if (customStatus != null) {
                    statusTitleText.visibility = View.GONE
                    statusText.text = customStatus
                } else {
                    val isHttps = DataStore.connectionTestURL.startsWith("https://", ignoreCase = true)
                    val handshakeType = if (isHttps) "HTTPS" else "HTTP"
                    if (displayedLatency > 0) {
                        statusTitleText.text = "$handshakeType 握手延迟"
                        statusTitleText.visibility = View.VISIBLE
                        statusText.text = "${displayedLatency}ms"
                    } else {
                        statusTitleText.visibility = View.GONE
                        if (latencyState.failed) {
                            statusText.text = app.getText(R.string.connection_test_fail)
                        } else if (latencyState.testing) {
                            statusText.text = app.getText(R.string.connection_test_testing)
                        } else if (cached == null && DataStore.showLandingIp && LandingIpManager.isCurrentlyQuerying()) {
                            statusText.text = context.getString(R.string.landing_ip_querying)
                        } else {
                            statusText.text = app.getString(R.string.vpn_connected)
                        }
                    }
                }
            } else {
                statusTitleText.visibility = View.GONE
                statusIpText.visibility = View.GONE
                statusText.text = customStatus ?: context.getText(
                    when (currentState) {
                        BaseService.State.Connecting -> R.string.connecting
                        BaseService.State.Stopping -> R.string.stopping
                        else -> R.string.not_connected
                    }
                )
            }
            TooltipCompat.setTooltipText(this, statusText.text)
        }
    }

    fun refreshDisplay() {
        runOnUi {
            updateThemeColors()
            if (currentState == BaseService.State.Connected) {
                btnIpDetail?.visibility = if (DataStore.showLandingIp) View.VISIBLE else View.GONE
                updateStatusViews()
                if (DataStore.showLandingIp && LandingIpManager.getCachedInfo() == null) {
                    refreshLandingIp(forceRefresh = false)
                }
            }
        }
    }

    fun changeState(state: BaseService.State) {
        runOnUi {
            currentState = state
            updateHideOnScroll()
            if (state == BaseService.State.Connected) {
                btnIpDetail?.visibility = if (DataStore.showLandingIp) View.VISIBLE else View.GONE
                updateStatusViews()
                if (DataStore.showLandingIp) {
                    refreshLandingIp(forceRefresh = false)
                }
            } else {
                btnIpDetail?.visibility = View.GONE
                resetLatencyState()
                LandingIpManager.clearCache()
                updateSpeed(0, 0)
                updateStatusViews()
            }
        }
    }

    fun refreshLandingIp(forceRefresh: Boolean = false) {
        runOnUi {
            if (currentState != BaseService.State.Connected) return@runOnUi
            if (!DataStore.showLandingIp) {
                btnIpDetail?.visibility = View.GONE
                updateStatusViews()
                return@runOnUi
            }
            val currentProfile = DataStore.selectedProxy
            val cached = LandingIpManager.getCachedInfo()
            if (!forceRefresh && cached != null && LandingIpManager.cachedProfileId == currentProfile) {
                btnIpDetail?.visibility = View.VISIBLE
                updateStatusViews()
                return@runOnUi
            }

            btnIpDetail?.visibility = View.VISIBLE
            if (forceRefresh || cached == null) {
                statusIpText.text = context.getString(R.string.landing_ip_querying)
                statusIpText.visibility = View.VISIBLE
            }

            val activity = context as? MainActivity
            val scope = activity?.lifecycleScope ?: return@runOnUi
            if (landingIpJob?.isActive == true && !forceRefresh) return@runOnUi
            landingIpJob?.cancel()
            landingIpJob = scope.launch {
                val result = LandingIpManager.queryLandingIp(currentProfile, forceRefresh = forceRefresh) { intermediateInfo ->
                    runOnUi {
                        if (currentState == BaseService.State.Connected && DataStore.showLandingIp && DataStore.selectedProxy == currentProfile) {
                            statusIpText.text = "${intermediateInfo.countryFlag} ${intermediateInfo.countryCode} ${intermediateInfo.ip}"
                            statusIpText.visibility = View.VISIBLE
                        }
                    }
                }
                if (currentState != BaseService.State.Connected || DataStore.selectedProxy != currentProfile) return@launch
                if (!DataStore.showLandingIp) {
                    btnIpDetail?.visibility = View.GONE
                    updateStatusViews()
                    return@launch
                }

                result.onSuccess { info ->
                    btnIpDetail?.visibility = View.VISIBLE
                    updateStatusViews()
                }.onFailure { err ->
                    Logs.w(err)
                    btnIpDetail?.visibility = View.VISIBLE
                    updateStatusViews()
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    fun updateSpeed(txRate: Long, rxRate: Long) {
        runOnUi {
            txText.text = "▲ ${
                context.getString(
                    R.string.speed, Formatter.formatFileSize(context, txRate)
                )
            }"
            rxText.text = "▼ ${
                context.getString(
                    R.string.speed, Formatter.formatFileSize(context, rxRate)
                )
            }"
        }
    }

    fun testConnection(silent: Boolean = false) {
        runOnUi {
            val activity = context as? MainActivity ?: return@runOnUi
            val key = probeKey() ?: return@runOnUi
            val service = activity.connection.service ?: return@runOnUi
            if (latencyState.synchronize(key)) {
                activeLatencyJob?.cancel()
                updateStatusViews()
            }
            val ticket = latencyState.begin(key, android.os.SystemClock.elapsedRealtime())
                ?: return@runOnUi
            updateStatusViews(customStatus = app.getText(R.string.connection_test_testing))
            activeLatencyJob = activity.lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val elapsed = activity.urlTest(service)
                    withContext(Dispatchers.Main) {
                        if (latencyState.complete(ticket, probeKey(), elapsed,
                                android.os.SystemClock.elapsedRealtime())) {
                            if (elapsed > 0) updateStatusViews()
                            else updateStatusViews(customStatus = app.getText(R.string.connection_test_fail))
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    withContext(Dispatchers.Main) {
                        if (latencyState.complete(ticket, probeKey(), 0,
                                android.os.SystemClock.elapsedRealtime())) {
                            Logs.w("testConnection error: $error")
                            updateStatusViews(customStatus = app.getText(R.string.connection_test_fail))
                            if (!silent) activity.snackbar(app.getString(
                                R.string.connection_test_error, error.readableMessage)).show()
                        }
                    }
                }
            }.also { job ->
                // Completion also runs if cancellation happens before the IO coroutine starts.
                // A blocking Binder call holds this slot until it has actually returned.
                job.invokeOnCompletion {
                    runOnUi {
                        latencyState.release(ticket)
                        if (activeLatencyJob === job) activeLatencyJob = null
                        // @author 雾晚: cancellation/session changes never schedule another probe.
                        updateStatusViews()
                    }
                }
            }
        }
    }

}
