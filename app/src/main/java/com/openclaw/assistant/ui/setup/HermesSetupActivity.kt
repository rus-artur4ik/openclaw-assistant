package com.openclaw.assistant.ui.setup

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.openclaw.assistant.backend.AgentBackendConfig
import com.openclaw.assistant.backend.BackendRepository
import com.openclaw.assistant.backend.HermesAutoConfig
import com.openclaw.assistant.backend.HermesCapabilityCache
import com.openclaw.assistant.backend.HermesLanScanner
import com.openclaw.assistant.backend.HermesSetupProbe
import com.openclaw.assistant.ui.backend.BackendEditorActivity
import com.openclaw.assistant.ui.theme.OpenClawAssistantTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.Inet4Address

/**
 * Guided "add a Hermes backend" flow.
 *
 * The manual editor asks for twelve fields before it can tell you whether any
 * of them are right. This asks for an address, finds the rest by talking to the
 * server, and says plainly what went wrong when it cannot.
 */
class HermesSetupActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // The app-wide dark scheme, not the bare Material default — the
            // wizard is reached from Settings and looked like a different app.
            OpenClawAssistantTheme {
                val vm: HermesSetupViewModel = viewModel()
                val state by vm.state.collectAsState()
                HermesSetupScreen(
                    state = state,
                    actions = HermesSetupActions(
                        onAddressChange = vm::setAddress,
                        onApiKeyChange = vm::setApiKey,
                        onConnect = vm::connect,
                        onScanLan = vm::scanLan,
                        onStopScan = vm::stopScan,
                        onScanQr = ::scanQr,
                        onPickFound = vm::pickFound,
                        onModelChange = vm::setModel,
                        onProviderChange = vm::setProvider,
                        onDisplayNameChange = vm::setDisplayName,
                        onMemoryScopeChange = vm::setMemoryScope,
                        onPrimaryChange = vm::setMakePrimary,
                        onContinue = vm::advance,
                        onBack = { if (!vm.goBack()) finish() },
                        onSave = { vm.save { finish() } },
                        onManualEntry = {
                            startActivity(BackendEditorActivity.intent(this, null))
                            finish()
                        },
                    ),
                )
            }
        }
    }

    /**
     * Reads a setup QR produced by `integrations/hermes-mobile-bridge/hermes_pair.py`.
     *
     * A payload that carries Hermes details fills the form in; anything else is
     * handed to the deep-link importer, which already knows every other form.
     */
    private fun scanQr() {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { barcode ->
                val raw = barcode.rawValue?.trim().orEmpty()
                val hermes = parsePairingPayload(raw)?.hermes
                if (hermes != null) {
                    val vm = androidx.lifecycle.ViewModelProvider(this)[HermesSetupViewModel::class.java]
                    vm.applyScannedPayload(hermes.baseUrl, hermes.apiKey, hermes.displayName)
                } else if (raw.startsWith("agentvoice://")) {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(raw)))
                    finish()
                } else {
                    android.widget.Toast.makeText(
                        this,
                        getString(com.openclaw.assistant.R.string.qr_scan_unavailable),
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }
            .addOnFailureListener {
                android.widget.Toast.makeText(
                    this,
                    getString(com.openclaw.assistant.R.string.qr_scan_unavailable),
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
    }

    companion object {
        fun intent(context: Context) = Intent(context, HermesSetupActivity::class.java)
    }
}

class HermesSetupViewModel(application: Application) : AndroidViewModel(application) {

    private val autoConfig = HermesAutoConfig()
    private val scanner = HermesLanScanner()
    private val repo = BackendRepository.getInstance(application)

    private val _state = MutableStateFlow(HermesSetupUiState())
    val state: StateFlow<HermesSetupUiState> = _state.asStateFlow()

    private var scanJob: Job? = null

    fun setAddress(value: String) = _state.update { it.copy(address = value, probe = null) }
    fun setApiKey(value: String) = _state.update { it.copy(apiKey = value, probe = null) }
    fun setModel(value: String) = _state.update { it.copy(model = value) }
    fun setProvider(value: String) = _state.update { it.copy(provider = value) }
    fun setDisplayName(value: String) = _state.update { it.copy(displayName = value) }
    fun setMemoryScope(value: String) = _state.update { it.copy(memoryScopeKey = value) }
    fun setMakePrimary(value: Boolean) = _state.update { it.copy(makePrimary = value) }

    fun applyScannedPayload(baseUrl: String, apiKey: String?, displayName: String?) {
        _state.update {
            it.copy(
                address = baseUrl,
                apiKey = apiKey.orEmpty(),
                displayName = displayName.orEmpty().ifBlank { it.displayName },
                probe = null,
            )
        }
        connect()
    }

    fun connect() {
        val current = _state.value
        if (current.address.isBlank()) return
        _state.update { it.copy(busy = true, probe = null) }
        viewModelScope.launch {
            val probe = autoConfig.probe(current.address, current.apiKey.takeIf { it.isNotBlank() })
            _state.update { state ->
                state.copy(
                    busy = false,
                    probe = probe,
                    capabilities = (probe as? HermesSetupProbe.Ready)?.capabilities,
                    // Default the name from the host, which is what the user
                    // recognises, not from the model or a generic label.
                    displayName = state.displayName.ifBlank { defaultNameFor(probe) },
                )
            }
        }
    }

    fun scanLan() {
        val subnet = localSubnet()
        if (subnet == null) {
            _state.update { it.copy(scanUnavailable = true) }
            return
        }
        val hosts = HermesLanScanner.hostsFor(subnet.first, subnet.second)
        if (hosts.isEmpty()) {
            _state.update { it.copy(scanUnavailable = true) }
            return
        }
        scanJob?.cancel()
        _state.update {
            it.copy(
                scanning = true,
                scanUnavailable = false,
                scanFinished = false,
                scanScanned = 0,
                scanTotal = hosts.size,
                scanResults = emptyList(),
            )
        }
        scanJob = viewModelScope.launch {
            // No key here on purpose: discovery talks to the whole subnet.
            scanner.scan(hosts).collect { progress ->
                _state.update {
                    it.copy(
                        scanScanned = progress.scanned,
                        scanTotal = progress.total,
                        scanResults = progress.found,
                        scanning = !progress.done,
                        scanFinished = progress.done,
                    )
                }
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _state.update { it.copy(scanning = false, scanFinished = true) }
    }

    fun pickFound(found: HermesLanScanner.Found) {
        stopScan()
        _state.update { it.copy(address = found.baseUrl, probe = null) }
        connect()
    }

    /** Moves to the next step, loading whatever that step needs first. */
    fun advance() {
        val current = _state.value
        when (current.step) {
            HermesSetupStep.CONNECT -> {
                val ready = current.probe as? HermesSetupProbe.Ready ?: return
                _state.update { it.copy(step = HermesSetupStep.REVIEW, busy = true) }
                viewModelScope.launch {
                    val suggested = autoConfig.suggestModel(
                        baseUrl = ready.baseUrl,
                        token = current.apiKey.takeIf { it.isNotBlank() },
                        capabilities = ready.capabilities,
                    )
                    _state.update { state ->
                        state.copy(
                            busy = false,
                            suggested = suggested,
                            model = state.model.ifBlank { suggested.model.orEmpty() },
                            provider = state.provider.ifBlank { suggested.provider.orEmpty() },
                        )
                    }
                }
            }
            HermesSetupStep.REVIEW -> _state.update { it.copy(step = HermesSetupStep.FINISH) }
            HermesSetupStep.FINISH -> Unit
        }
    }

    /** Returns false when there is nowhere left to go back to. */
    fun goBack(): Boolean {
        val current = _state.value
        return when (current.step) {
            HermesSetupStep.CONNECT -> false
            HermesSetupStep.REVIEW -> { _state.update { it.copy(step = HermesSetupStep.CONNECT) }; true }
            HermesSetupStep.FINISH -> { _state.update { it.copy(step = HermesSetupStep.REVIEW) }; true }
        }
    }

    fun save(onSaved: () -> Unit) {
        val current = _state.value
        val baseUrl = current.resolvedBaseUrl ?: return
        val config: AgentBackendConfig = autoConfig.buildConfig(
            baseUrl = baseUrl,
            token = current.apiKey,
            displayName = current.displayName,
            model = current.model,
            provider = current.provider,
            memoryScopeKey = current.memoryScopeKey,
            isPrimary = current.makePrimary,
        )
        repo.upsert(config)
        if (current.makePrimary) repo.setPrimary(config.id)
        // The probe cached whatever this server said before it was a backend.
        HermesCapabilityCache.invalidate(config.id)
        _state.update { it.copy(savedName = config.displayName) }
        onSaved()
    }

    private fun defaultNameFor(probe: HermesSetupProbe): String {
        val host = probe.baseUrlOrNull?.let { runCatching { java.net.URI(it).host }.getOrNull() }
        return host?.let { "Hermes ($it)" } ?: "Hermes Agent"
    }

    /** The device's IPv4 address and prefix length on the active network. */
    private fun localSubnet(): Pair<String, Int>? {
        val connectivity = getApplication<Application>()
            .getSystemService(ConnectivityManager::class.java) ?: return null
        val network = connectivity.activeNetwork ?: return null
        val properties = connectivity.getLinkProperties(network) ?: return null
        val address: LinkAddress = properties.linkAddresses.firstOrNull {
            it.address is Inet4Address && !it.address.isLoopbackAddress && it.address.isSiteLocalAddress
        } ?: return null
        return address.address.hostAddress?.let { it to address.prefixLength }
    }
}

private fun MutableStateFlow<HermesSetupUiState>.update(
    transform: (HermesSetupUiState) -> HermesSetupUiState,
) {
    value = transform(value)
}
