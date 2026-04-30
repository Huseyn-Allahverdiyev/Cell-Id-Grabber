package com.project.cellidgrabber

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.telephony.CellInfo
import android.telephony.CellInfoCdma
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoTdscdma
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager
import android.widget.Button
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 101
        private const val IP_API_URL = "https://ipapi.co/json/"
    }

    private lateinit var telephonyManager: TelephonyManager
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var wifiManager: WifiManager
    private lateinit var locationManager: LocationManager

    private lateinit var cellTowerTable: TableLayout
    private lateinit var refreshButton: Button
    private lateinit var clearButton: Button
    private lateinit var saveButton: Button
    private lateinit var statusTextView: TextView
    private lateinit var summaryTextView: TextView

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()

    private var latestSnapshot: NetworkSnapshot? = null
    private val currentCells = mutableListOf<CellRecord>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        cellTowerTable = findViewById(R.id.cellTowerTable)
        refreshButton = findViewById(R.id.refreshButton)
        clearButton = findViewById(R.id.clearButton)
        saveButton = findViewById(R.id.saveButton)
        statusTextView = findViewById(R.id.statusText)
        summaryTextView = findViewById(R.id.summaryText)

        refreshButton.setOnClickListener { refreshAllData() }
        clearButton.setOnClickListener { clearData() }
        saveButton.setOnClickListener { saveDataAsJson() }

        checkPermissions()
        statusTextView.text = "Ready. Refresh bas."
    }

    override fun onDestroy() {
        super.onDestroy()
        ioExecutor.shutdown()
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        val toRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (toRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                toRequest.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        }
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarse = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return fine || coarse
    }

    private fun hasPhoneStatePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun refreshAllData() {
        if (!hasLocationPermission() || !hasPhoneStatePermission()) {
            statusTextView.text = "Permission yoxdur. Yenidən istənilir..."
            checkPermissions()
            return
        }

        statusTextView.text = "Data yenilənir..."

        collectLocation { locationSnapshot ->
            fetchPublicIpInfo { publicIpSnapshot ->
                fetchFreshCellInfo { cellEnvironment, cells ->
                    currentCells.clear()
                    currentCells.addAll(cells)

                    val activeTransport = getActiveTransportName()
                    val localIp = getLocalIpv4FromActiveNetwork()
                    val linkState = getLinkState()
                    val wifiSnapshot = getWifiSnapshot(publicIpSnapshot, activeTransport)
                    val servingCell = cells.firstOrNull { it.registered.equals("true", ignoreCase = true) }

                    val snapshot = NetworkSnapshot(
                        timestamp = currentIsoTime(),
                        activeTransport = activeTransport,
                        localIp = localIp,
                        networkValidated = linkState.validated,
                        internetCapability = linkState.internetCapability,
                        metered = linkState.metered,
                        downstreamKbps = linkState.downKbps,
                        upstreamKbps = linkState.upKbps,
                        publicIp = publicIpSnapshot,
                        location = locationSnapshot,
                        wifi = wifiSnapshot,
                        cellEnvironment = cellEnvironment,
                        servingCell = servingCell,
                        cells = cells
                    )

                    latestSnapshot = snapshot
                    bindSnapshotToUi(snapshot)
                }
            }
        }
    }

    private fun clearData() {
        currentCells.clear()
        latestSnapshot = null
        summaryTextView.text = ""
        statusTextView.text = "Data silindi."
        updateTable(emptyList())
    }

    private fun bindSnapshotToUi(snapshot: NetworkSnapshot) {
        val serving = snapshot.servingCell

        val summary = buildString {
            appendLine("Tarix/Saat: ${snapshot.timestamp}")
            appendLine("Aktiv transport: ${snapshot.activeTransport}")
            appendLine("Local IP: ${snapshot.localIp}")
            appendLine("Internet validated: ${snapshot.networkValidated}")
            appendLine("Metered: ${snapshot.metered}")
            appendLine("Speed (Kbps): down=${snapshot.downstreamKbps}, up=${snapshot.upstreamKbps}")
            appendLine()

            appendLine("PUBLIC IP / INTERNET")
            appendLine("Public IP: ${snapshot.publicIp.ip}")
            appendLine("Provider / ISP / ORG: ${snapshot.publicIp.providerName}")
            appendLine("ASN: ${snapshot.publicIp.asn}")
            appendLine("IP Geo: ${snapshot.publicIp.city}, ${snapshot.publicIp.region}, ${snapshot.publicIp.country}")
            appendLine("IP Geo Lat/Lng: ${snapshot.publicIp.latitude ?: "N/A"}, ${snapshot.publicIp.longitude ?: "N/A"}")
            appendLine("IP lookup xətası: ${snapshot.publicIp.lookupError}")
            appendLine()

            appendLine("DEVICE LOCATION")
            appendLine("Lat/Lng: ${snapshot.location.latitude ?: "N/A"}, ${snapshot.location.longitude ?: "N/A"}")
            appendLine("Accuracy(m): ${snapshot.location.accuracyMeters ?: "N/A"}")
            appendLine("Provider: ${snapshot.location.provider}")
            appendLine("Address: ${snapshot.location.address}")
            appendLine()

            appendLine("WIFI")
            appendLine("SSID: ${snapshot.wifi?.ssid ?: "N/A"}")
            appendLine("BSSID: ${snapshot.wifi?.bssid ?: "N/A"}")
            appendLine("RSSI: ${snapshot.wifi?.rssi ?: "N/A"}")
            appendLine("Frequency: ${snapshot.wifi?.frequencyMHz ?: "N/A"}")
            appendLine("Link speed: ${snapshot.wifi?.linkSpeedMbps ?: "N/A"} Mbps")
            appendLine("Gateway: ${snapshot.wifi?.gateway ?: "N/A"}")
            appendLine("DNS: ${snapshot.wifi?.dns ?: "N/A"}")
            appendLine("WiFi Provider: ${snapshot.wifi?.providerName ?: "N/A"}")
            appendLine()

            appendLine("CELL ENVIRONMENT")
            appendLine("Operator: ${snapshot.cellEnvironment.operatorName}")
            appendLine("Operator code: ${snapshot.cellEnvironment.operatorCode}")
            appendLine("Country ISO: ${snapshot.cellEnvironment.countryIso}")
            appendLine("Roaming: ${snapshot.cellEnvironment.roaming}")
            appendLine("SIM State: ${snapshot.cellEnvironment.simState}")
            appendLine("Data State: ${snapshot.cellEnvironment.dataState}")
            appendLine("Voice Type: ${snapshot.cellEnvironment.voiceNetworkType}")
            appendLine("Data Type: ${snapshot.cellEnvironment.dataNetworkType}")
            appendLine()

            appendLine("SERVING CELL")
            appendLine("Radio: ${serving?.radioType ?: "N/A"}")
            appendLine("Raw Cell ID: ${serving?.rawCellId ?: "N/A"}")
            appendLine("Unique ID: ${serving?.uniqueId ?: "N/A"}")
            appendLine("Site / Base ID: ${serving?.siteId ?: "N/A"}")
            appendLine("Sector / Local Cell: ${serving?.sectorId ?: "N/A"}")
            appendLine("Area Code: ${serving?.areaCode ?: "N/A"}")
            appendLine("PCI/PSC/BSIC: ${serving?.pciPsc ?: "N/A"}")
            appendLine("Channel: ${serving?.channel ?: "N/A"}")
            appendLine("Signal dBm: ${serving?.signalDbm ?: "N/A"}")
            appendLine("Level: ${serving?.signalLevel ?: "N/A"}")
            appendLine()
            appendLine("Ümumi cell sayı: ${snapshot.cells.size}")
        }

        summaryTextView.text = summary
        statusTextView.text = "Hazırdır. Cell sayı: ${snapshot.cells.size}"
        updateTable(snapshot.cells)
    }

    private fun saveDataAsJson() {
        val snapshot = latestSnapshot
        if (snapshot == null) {
            statusTextView.text = "Yadda saxlanacaq data yoxdur."
            return
        }

        try {
            val fileName = "aky_network_data_${System.currentTimeMillis()}.json"

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS)
            }

            val uri = contentResolver.insert(MediaStore.Files.getContentUri("external"), values)

            if (uri == null) {
                statusTextView.text = "JSON fayl yaradıla bilmədi."
                return
            }

            contentResolver.openOutputStream(uri)?.use { stream ->
                val prettyJson = snapshot.toJson().toString(2)
                stream.write(prettyJson.toByteArray())
            }

            statusTextView.text = "Saved: $fileName"
        } catch (e: Exception) {
            statusTextView.text = "Save xətası: ${e.message}"
        }
    }

    // -------------------------------------------------
    // LOCATION (yalnız permission veriləndə)
    // -------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun collectLocation(callback: (LocationSnapshot) -> Unit) {
        if (!hasLocationPermission()) {
            callback(LocationSnapshot())
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val provider = when {
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                else -> null
            }

            if (provider == null) {
                val fallback = getBestLastKnownLocation()
                if (fallback != null) {
                    reverseGeocodeAndReturn(fallback, callback)
                } else {
                    callback(LocationSnapshot())
                }
                return
            }

            try {
                locationManager.getCurrentLocation(
                    provider,
                    CancellationSignal(),
                    mainExecutor
                ) { location ->
                    if (location != null) {
                        reverseGeocodeAndReturn(location, callback)
                    } else {
                        val fallback = getBestLastKnownLocation()
                        if (fallback != null) {
                            reverseGeocodeAndReturn(fallback, callback)
                        } else {
                            callback(LocationSnapshot())
                        }
                    }
                }
            } catch (e: Exception) {
                val fallback = getBestLastKnownLocation()
                if (fallback != null) {
                    reverseGeocodeAndReturn(fallback, callback)
                } else {
                    callback(LocationSnapshot())
                }
            }
        } else {
            val fallback = getBestLastKnownLocation()
            if (fallback != null) {
                reverseGeocodeAndReturn(fallback, callback)
            } else {
                callback(LocationSnapshot())
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun getBestLastKnownLocation(): Location? {
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )

        val locations = providers.mapNotNull { provider ->
            try {
                locationManager.getLastKnownLocation(provider)
            } catch (_: Exception) {
                null
            }
        }

        return locations.maxWithOrNull(
            compareBy<Location> { it.accuracy }.thenByDescending { it.time }
        )
    }

    private fun reverseGeocodeAndReturn(location: Location, callback: (LocationSnapshot) -> Unit) {
        ioExecutor.execute {
            var addressText = "N/A"

            try {
                if (Geocoder.isPresent()) {
                    val geocoder = Geocoder(this, Locale.getDefault())

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val done = AtomicBoolean(false)

                        geocoder.getFromLocation(
                            location.latitude,
                            location.longitude,
                            1,
                            object : Geocoder.GeocodeListener {
                                override fun onGeocode(addresses: MutableList<Address>) {
                                    addressText = addresses.firstOrNull()?.getAddressLine(0) ?: "N/A"
                                    if (done.compareAndSet(false, true)) {
                                        mainHandler.post {
                                            callback(
                                                LocationSnapshot(
                                                    latitude = location.latitude,
                                                    longitude = location.longitude,
                                                    accuracyMeters = location.accuracy,
                                                    provider = location.provider ?: "N/A",
                                                    address = addressText
                                                )
                                            )
                                        }
                                    }
                                }

                                override fun onError(errorMessage: String?) {
                                    if (done.compareAndSet(false, true)) {
                                        mainHandler.post {
                                            callback(
                                                LocationSnapshot(
                                                    latitude = location.latitude,
                                                    longitude = location.longitude,
                                                    accuracyMeters = location.accuracy,
                                                    provider = location.provider ?: "N/A",
                                                    address = "N/A"
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        )

                        mainHandler.postDelayed({
                            if (done.compareAndSet(false, true)) {
                                callback(
                                    LocationSnapshot(
                                        latitude = location.latitude,
                                        longitude = location.longitude,
                                        accuracyMeters = location.accuracy,
                                        provider = location.provider ?: "N/A",
                                        address = addressText
                                    )
                                )
                            }
                        }, 1800)

                        return@execute
                    } else {
                        @Suppress("DEPRECATION")
                        val list = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                        addressText = list?.firstOrNull()?.getAddressLine(0) ?: "N/A"
                    }
                }
            } catch (_: Exception) {
                addressText = "N/A"
            }

            mainHandler.post {
                callback(
                    LocationSnapshot(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        accuracyMeters = location.accuracy,
                        provider = location.provider ?: "N/A",
                        address = addressText
                    )
                )
            }
        }
    }

    // -------------------------------------------------
    // PUBLIC IP / ISP / IP GEO
    // -------------------------------------------------

    private fun fetchPublicIpInfo(callback: (PublicIpSnapshot) -> Unit) {
        ioExecutor.execute {
            try {
                val response = httpGetJson(IP_API_URL)

                if (response.optBoolean("error", false)) {
                    mainHandler.post {
                        callback(
                            PublicIpSnapshot(
                                lookupError = response.optString("reason", "Unknown API error")
                            )
                        )
                    }
                    return@execute
                }

                val snapshot = PublicIpSnapshot(
                    ip = response.optString("ip", "N/A"),
                    providerName = response.optString("org", "N/A"),
                    asn = response.optString("asn", "N/A"),
                    city = response.optString("city", "N/A"),
                    region = response.optString("region", "N/A"),
                    country = response.optString("country_name", response.optString("country", "N/A")),
                    latitude = response.optDoubleOrNull("latitude"),
                    longitude = response.optDoubleOrNull("longitude"),
                    timezone = response.optString("timezone", "N/A"),
                    lookupError = "N/A"
                )

                mainHandler.post { callback(snapshot) }
            } catch (e: Exception) {
                mainHandler.post {
                    callback(
                        PublicIpSnapshot(
                            lookupError = e.message ?: "Public IP lookup failed"
                        )
                    )
                }
            }
        }
    }

    private fun httpGetJson(urlString: String): JSONObject {
        val url = URL(urlString)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 10000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "AKY-Network-Monitor/1.0")
        }

        return try {
            val inputStream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val text = BufferedReader(InputStreamReader(inputStream)).use { it.readText() }
            JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    // -------------------------------------------------
    // NETWORK / WIFI
    // -------------------------------------------------

    private fun getActiveTransportName(): String {
        val network = connectivityManager.activeNetwork ?: return "NONE"
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return "NONE"

        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "UNKNOWN"
        }
    }

    private fun getLinkState(): LinkState {
        val network = connectivityManager.activeNetwork ?: return LinkState()
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return LinkState()

        return LinkState(
            validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            internetCapability = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            downKbps = caps.linkDownstreamBandwidthKbps,
            upKbps = caps.linkUpstreamBandwidthKbps
        )
    }

    private fun getLocalIpv4FromActiveNetwork(): String {
        val network = connectivityManager.activeNetwork ?: return "N/A"
        val linkProperties = connectivityManager.getLinkProperties(network) ?: return "N/A"

        val ipv4 = linkProperties.linkAddresses
            .mapNotNull { it.address }
            .firstOrNull { address -> !address.isLoopbackAddress && address is Inet4Address }

        return ipv4?.hostAddress ?: "N/A"
    }

    @SuppressLint("MissingPermission", "HardwareIds")
    private fun getWifiSnapshot(publicIp: PublicIpSnapshot, activeTransport: String): WifiSnapshot? {
        if (activeTransport != "WIFI") return null

        val network = connectivityManager.activeNetwork ?: return null
        val linkProperties = connectivityManager.getLinkProperties(network)

        @Suppress("DEPRECATION")
        val info = wifiManager.connectionInfo ?: return null

        val ssid = sanitizeSsid(info.ssid)
        val bssid = info.bssid ?: "N/A"
        val rssi = if (info.rssi == Int.MIN_VALUE) "N/A" else "${info.rssi}"
        val frequency = if (info.frequency <= 0) "N/A" else "${info.frequency}"
        val linkSpeed = if (info.linkSpeed <= 0) "N/A" else "${info.linkSpeed}"

        return WifiSnapshot(
            ssid = ssid,
            bssid = bssid,
            rssi = rssi,
            frequencyMHz = frequency,
            linkSpeedMbps = linkSpeed,
            gateway = getGateway(linkProperties),
            dns = getDns(linkProperties),
            providerName = publicIp.providerName
        )
    }

    private fun getGateway(linkProperties: LinkProperties?): String {
        if (linkProperties == null) return "N/A"
        val route = linkProperties.routes.firstOrNull { it.hasGateway() }
        return route?.gateway?.hostAddress ?: "N/A"
    }

    private fun getDns(linkProperties: LinkProperties?): String {
        if (linkProperties == null) return "N/A"
        return if (linkProperties.dnsServers.isEmpty()) {
            "N/A"
        } else {
            linkProperties.dnsServers.joinToString(", ") { it.hostAddress ?: "N/A" }
        }
    }

    private fun sanitizeSsid(raw: String?): String {
        if (raw.isNullOrBlank()) return "N/A"
        return raw.removePrefix("\"").removeSuffix("\"")
    }

    // -------------------------------------------------
    // CELL INFO
    // -------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun fetchFreshCellInfo(callback: (CellEnvironment, List<CellRecord>) -> Unit) {
        val env = buildCellEnvironment()

        if (!hasLocationPermission()) {
            callback(env, emptyList())
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var delivered = false

            val fallbackRunnable = Runnable {
                if (!delivered) {
                    delivered = true
                    val cached = try {
                        telephonyManager.allCellInfo ?: emptyList()
                    } catch (_: Exception) {
                        emptyList()
                    }
                    callback(buildCellEnvironment(), parseCellInfoList(cached))
                }
            }

            mainHandler.postDelayed(fallbackRunnable, 1800)

            try {
                telephonyManager.requestCellInfoUpdate(
                    mainExecutor,
                    object : TelephonyManager.CellInfoCallback() {
                        override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                            if (delivered) return
                            delivered = true
                            mainHandler.removeCallbacks(fallbackRunnable)
                            callback(buildCellEnvironment(), parseCellInfoList(cellInfo))
                        }
                    }
                )
            } catch (_: Exception) {
                mainHandler.removeCallbacks(fallbackRunnable)
                val cached = try {
                    telephonyManager.allCellInfo ?: emptyList()
                } catch (_: Exception) {
                    emptyList()
                }
                callback(buildCellEnvironment(), parseCellInfoList(cached))
            }
        } else {
            val list = try {
                telephonyManager.allCellInfo ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
            callback(env, parseCellInfoList(list))
        }
    }

    @SuppressLint("MissingPermission")
    private fun buildCellEnvironment(): CellEnvironment {
        return CellEnvironment(
            operatorName = telephonyManager.networkOperatorName ?: "N/A",
            operatorCode = telephonyManager.networkOperator ?: "N/A",
            countryIso = telephonyManager.networkCountryIso ?: "N/A",
            roaming = telephonyManager.isNetworkRoaming.toString(),
            simState = getSimStateName(telephonyManager.simState),
            dataState = getDataStateName(telephonyManager.dataState),
            voiceNetworkType = getNetworkTypeName(telephonyManager.voiceNetworkType),
            dataNetworkType = getNetworkTypeName(telephonyManager.dataNetworkType)
        )
    }

    private fun parseCellInfoList(cellInfoList: List<CellInfo>): List<CellRecord> {
        val env = buildCellEnvironment()
        val result = mutableListOf<CellRecord>()

        for (cell in cellInfoList) {
            when (cell) {
                is CellInfoLte -> {
                    val id = cell.cellIdentity
                    val signal = cell.cellSignalStrength
                    val ci = id.ci
                    val rawCellId = ci.safeString()

                    val siteId = if (ci >= 0) (ci ushr 8).toString() else "N/A"
                    val sectorId = if (ci >= 0) (ci and 0xFF).toString() else "N/A"

                    val unique = listOf(
                        id.mccString ?: "N/A",
                        id.mncString ?: "N/A",
                        id.tac.safeString(),
                        rawCellId,
                        id.pci.safeString()
                    ).joinToString("-")

                    result.add(
                        CellRecord(
                            radioType = "LTE",
                            registered = cell.isRegistered.toString(),
                            mcc = id.mccString ?: "N/A",
                            mnc = id.mncString ?: "N/A",
                            areaCode = id.tac.safeString(),
                            rawCellId = rawCellId,
                            uniqueId = unique,
                            siteId = siteId,
                            sectorId = sectorId,
                            pciPsc = id.pci.safeString(),
                            channel = id.earfcn.safeString(),
                            signalDbm = signal.dbm.safeString(),
                            signalLevel = signal.level.safeString(),
                            timingAdvance = signal.timingAdvance.safeString(),
                            operator = env.operatorName,
                            countryIso = env.countryIso,
                            timestampNanos = cell.timeStamp.toString()
                        )
                    )
                }

                is CellInfoNr -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val id = cell.cellIdentity
                        val signal = cell.cellSignalStrength
                        val rawCellId = id.nci.toString()

                        val unique = listOf(
                            id.mccString ?: "N/A",
                            id.mncString ?: "N/A",
                            id.tac.safeString(),
                            rawCellId,
                            id.pci.safeString()
                        ).joinToString("-")

                        result.add(
                            CellRecord(
                                radioType = "NR",
                                registered = cell.isRegistered.toString(),
                                mcc = id.mccString ?: "N/A",
                                mnc = id.mncString ?: "N/A",
                                areaCode = id.tac.safeString(),
                                rawCellId = rawCellId,
                                uniqueId = unique,
                                siteId = "Operator-defined",
                                sectorId = id.pci.safeString(),
                                pciPsc = id.pci.safeString(),
                                channel = id.nrarfcn.safeString(),
                                signalDbm = signal.dbm.safeString(),
                                signalLevel = signal.level.safeString(),
                                timingAdvance = "N/A",
                                operator = env.operatorName,
                                countryIso = env.countryIso,
                                timestampNanos = cell.timeStamp.toString()
                            )
                        )
                    }
                }

                is CellInfoGsm -> {
                    val id = cell.cellIdentity
                    val signal = cell.cellSignalStrength
                    val rawCellId = id.cid.safeString()

                    val unique = listOf(
                        id.mccString ?: "N/A",
                        id.mncString ?: "N/A",
                        id.lac.safeString(),
                        rawCellId
                    ).joinToString("-")

                    result.add(
                        CellRecord(
                            radioType = "GSM",
                            registered = cell.isRegistered.toString(),
                            mcc = id.mccString ?: "N/A",
                            mnc = id.mncString ?: "N/A",
                            areaCode = id.lac.safeString(),
                            rawCellId = rawCellId,
                            uniqueId = unique,
                            siteId = "N/A",
                            sectorId = id.bsic.safeString(),
                            pciPsc = id.bsic.safeString(),
                            channel = id.arfcn.safeString(),
                            signalDbm = signal.dbm.safeString(),
                            signalLevel = signal.level.safeString(),
                            timingAdvance = "N/A",
                            operator = env.operatorName,
                            countryIso = env.countryIso,
                            timestampNanos = cell.timeStamp.toString()
                        )
                    )
                }

                is CellInfoWcdma -> {
                    val id = cell.cellIdentity
                    val signal = cell.cellSignalStrength
                    val rawCellId = id.cid.safeString()

                    val unique = listOf(
                        id.mccString ?: "N/A",
                        id.mncString ?: "N/A",
                        id.lac.safeString(),
                        rawCellId,
                        id.psc.safeString()
                    ).joinToString("-")

                    result.add(
                        CellRecord(
                            radioType = "WCDMA",
                            registered = cell.isRegistered.toString(),
                            mcc = id.mccString ?: "N/A",
                            mnc = id.mncString ?: "N/A",
                            areaCode = id.lac.safeString(),
                            rawCellId = rawCellId,
                            uniqueId = unique,
                            siteId = "N/A",
                            sectorId = id.psc.safeString(),
                            pciPsc = id.psc.safeString(),
                            channel = id.uarfcn.safeString(),
                            signalDbm = signal.dbm.safeString(),
                            signalLevel = signal.level.safeString(),
                            timingAdvance = "N/A",
                            operator = env.operatorName,
                            countryIso = env.countryIso,
                            timestampNanos = cell.timeStamp.toString()
                        )
                    )
                }

                is CellInfoTdscdma -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val id = cell.cellIdentity
                        val signal = cell.cellSignalStrength
                        val rawCellId = id.cid.safeString()

                        val unique = listOf(
                            id.mccString ?: "N/A",
                            id.mncString ?: "N/A",
                            id.lac.safeString(),
                            rawCellId,
                            id.cpid.safeString()
                        ).joinToString("-")

                        result.add(
                            CellRecord(
                                radioType = "TDSCDMA",
                                registered = cell.isRegistered.toString(),
                                mcc = id.mccString ?: "N/A",
                                mnc = id.mncString ?: "N/A",
                                areaCode = id.lac.safeString(),
                                rawCellId = rawCellId,
                                uniqueId = unique,
                                siteId = "N/A",
                                sectorId = id.cpid.safeString(),
                                pciPsc = id.cpid.safeString(),
                                channel = id.uarfcn.safeString(),
                                signalDbm = signal.dbm.safeString(),
                                signalLevel = signal.level.safeString(),
                                timingAdvance = "N/A",
                                operator = env.operatorName,
                                countryIso = env.countryIso,
                                timestampNanos = cell.timeStamp.toString()
                            )
                        )
                    }
                }

                is CellInfoCdma -> {
                    val id = cell.cellIdentity
                    val signal = cell.cellSignalStrength
                    val rawCellId = id.basestationId.safeString()

                    val unique = listOf(
                        id.networkId.safeString(),
                        rawCellId,
                        id.systemId.safeString()
                    ).joinToString("-")

                    result.add(
                        CellRecord(
                            radioType = "CDMA",
                            registered = cell.isRegistered.toString(),
                            mcc = "N/A",
                            mnc = "N/A",
                            areaCode = id.networkId.safeString(),
                            rawCellId = rawCellId,
                            uniqueId = unique,
                            siteId = id.systemId.safeString(),
                            sectorId = "N/A",
                            pciPsc = id.systemId.safeString(),
                            channel = "N/A",
                            signalDbm = signal.dbm.safeString(),
                            signalLevel = signal.level.safeString(),
                            timingAdvance = "N/A",
                            operator = env.operatorName,
                            countryIso = env.countryIso,
                            timestampNanos = cell.timeStamp.toString()
                        )
                    )
                }
            }
        }

        return result.sortedByDescending { it.registered.equals("true", ignoreCase = true) }
    }

    // -------------------------------------------------
    // TABLE UI
    // -------------------------------------------------

    private fun updateTable(data: List<CellRecord>) {
        val removeCount = (cellTowerTable.childCount - 1).coerceAtLeast(0)
        if (removeCount > 0) {
            cellTowerTable.removeViews(1, removeCount)
        }

        data.forEachIndexed { index, tower ->
            addRow(index + 1, tower)
        }
    }

    private fun addRow(index: Int, tower: CellRecord) {
        val row = TableRow(this)

        fun makeCell(text: String): TextView {
            return TextView(this).apply {
                this.text = text
                setPadding(10, 10, 10, 10)
                setBackgroundColor(0xFFF6F6F6.toInt())
            }
        }

        row.addView(makeCell(index.toString()))
        row.addView(makeCell(tower.radioType))
        row.addView(makeCell(tower.registered))
        row.addView(makeCell(tower.mcc))
        row.addView(makeCell(tower.mnc))
        row.addView(makeCell(tower.areaCode))
        row.addView(makeCell(tower.rawCellId))
        row.addView(makeCell(tower.siteId))
        row.addView(makeCell(tower.sectorId))
        row.addView(makeCell(tower.uniqueId))
        row.addView(makeCell(tower.pciPsc))
        row.addView(makeCell(tower.channel))
        row.addView(makeCell(tower.signalDbm))
        row.addView(makeCell(tower.signalLevel))
        row.addView(makeCell(tower.timingAdvance))
        row.addView(makeCell(tower.operator))
        row.addView(makeCell(tower.countryIso))
        row.addView(makeCell(tower.timestampNanos))

        cellTowerTable.addView(row)
    }

    // -------------------------------------------------
    // HELPERS
    // -------------------------------------------------

    private fun getNetworkTypeName(type: Int): String = when (type) {
        TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
        TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
        TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"
        TelephonyManager.NETWORK_TYPE_CDMA -> "CDMA"
        TelephonyManager.NETWORK_TYPE_EVDO_0 -> "EVDO_0"
        TelephonyManager.NETWORK_TYPE_EVDO_A -> "EVDO_A"
        TelephonyManager.NETWORK_TYPE_1xRTT -> "1xRTT"
        TelephonyManager.NETWORK_TYPE_HSDPA -> "HSDPA"
        TelephonyManager.NETWORK_TYPE_HSUPA -> "HSUPA"
        TelephonyManager.NETWORK_TYPE_HSPA -> "HSPA"
        TelephonyManager.NETWORK_TYPE_IDEN -> "IDEN"
        TelephonyManager.NETWORK_TYPE_EVDO_B -> "EVDO_B"
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
        TelephonyManager.NETWORK_TYPE_EHRPD -> "EHRPD"
        TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPA+"
        TelephonyManager.NETWORK_TYPE_GSM -> "GSM"
        TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "TD_SCDMA"
        TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN"
        TelephonyManager.NETWORK_TYPE_NR -> "NR/5G"
        else -> "UNKNOWN"
    }

    private fun getSimStateName(state: Int): String = when (state) {
        TelephonyManager.SIM_STATE_UNKNOWN -> "UNKNOWN"
        TelephonyManager.SIM_STATE_ABSENT -> "ABSENT"
        TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN_REQUIRED"
        TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK_REQUIRED"
        TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "NETWORK_LOCKED"
        TelephonyManager.SIM_STATE_READY -> "READY"
        TelephonyManager.SIM_STATE_NOT_READY -> "NOT_READY"
        TelephonyManager.SIM_STATE_PERM_DISABLED -> "PERM_DISABLED"
        TelephonyManager.SIM_STATE_CARD_IO_ERROR -> "CARD_IO_ERROR"
        TelephonyManager.SIM_STATE_CARD_RESTRICTED -> "CARD_RESTRICTED"
        else -> "OTHER"
    }

    private fun getDataStateName(state: Int): String = when (state) {
        TelephonyManager.DATA_CONNECTED -> "CONNECTED"
        TelephonyManager.DATA_CONNECTING -> "CONNECTING"
        TelephonyManager.DATA_DISCONNECTED -> "DISCONNECTED"
        TelephonyManager.DATA_SUSPENDED -> "SUSPENDED"
        else -> "UNKNOWN"
    }

    private fun currentIsoTime(): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date())
    }

    private fun Int.safeString(): String {
        return if (this == Int.MAX_VALUE || this == Int.MIN_VALUE || this < 0) "N/A" else this.toString()
    }

    private fun Long.safeString(): String {
        return if (this < 0) "N/A" else this.toString()
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? {
        return if (has(key) && !isNull(key)) optDouble(key) else null
    }

    // -------------------------------------------------
    // PERMISSION CALLBACK
    // -------------------------------------------------

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            statusTextView.text = if (allGranted) {
                "Permission verildi. Refresh et."
            } else {
                "Bəzi permission-lar verilmədi. Data natamam ola bilər."
            }
        }
    }

    // -------------------------------------------------
    // DATA CLASSES
    // -------------------------------------------------

    data class LinkState(
        val validated: Boolean = false,
        val internetCapability: Boolean = false,
        val metered: Boolean = false,
        val downKbps: Int = 0,
        val upKbps: Int = 0
    )

    data class LocationSnapshot(
        val latitude: Double? = null,
        val longitude: Double? = null,
        val accuracyMeters: Float? = null,
        val provider: String = "N/A",
        val address: String = "N/A"
    ) {
        fun toJson(): JSONObject {
            return JSONObject().apply {
                put("latitude", latitude ?: JSONObject.NULL)
                put("longitude", longitude ?: JSONObject.NULL)
                put("accuracyMeters", accuracyMeters ?: JSONObject.NULL)
                put("provider", provider)
                put("address", address)
            }
        }
    }

    data class PublicIpSnapshot(
        val ip: String = "N/A",
        val providerName: String = "N/A",
        val asn: String = "N/A",
        val city: String = "N/A",
        val region: String = "N/A",
        val country: String = "N/A",
        val latitude: Double? = null,
        val longitude: Double? = null,
        val timezone: String = "N/A",
        val lookupError: String = "N/A"
    ) {
        fun toJson(): JSONObject {
            return JSONObject().apply {
                put("ip", ip)
                put("providerName", providerName)
                put("asn", asn)
                put("city", city)
                put("region", region)
                put("country", country)
                put("latitude", latitude ?: JSONObject.NULL)
                put("longitude", longitude ?: JSONObject.NULL)
                put("timezone", timezone)
                put("lookupError", lookupError)
            }
        }
    }

    data class WifiSnapshot(
        val ssid: String = "N/A",
        val bssid: String = "N/A",
        val rssi: String = "N/A",
        val frequencyMHz: String = "N/A",
        val linkSpeedMbps: String = "N/A",
        val gateway: String = "N/A",
        val dns: String = "N/A",
        val providerName: String = "N/A"
    ) {
        fun toJson(): JSONObject {
            return JSONObject().apply {
                put("ssid", ssid)
                put("bssid", bssid)
                put("rssi", rssi)
                put("frequencyMHz", frequencyMHz)
                put("linkSpeedMbps", linkSpeedMbps)
                put("gateway", gateway)
                put("dns", dns)
                put("providerName", providerName)
            }
        }
    }

    data class CellEnvironment(
        val operatorName: String = "N/A",
        val operatorCode: String = "N/A",
        val countryIso: String = "N/A",
        val roaming: String = "N/A",
        val simState: String = "N/A",
        val dataState: String = "N/A",
        val voiceNetworkType: String = "N/A",
        val dataNetworkType: String = "N/A"
    ) {
        fun toJson(): JSONObject {
            return JSONObject().apply {
                put("operatorName", operatorName)
                put("operatorCode", operatorCode)
                put("countryIso", countryIso)
                put("roaming", roaming)
                put("simState", simState)
                put("dataState", dataState)
                put("voiceNetworkType", voiceNetworkType)
                put("dataNetworkType", dataNetworkType)
            }
        }
    }

    data class CellRecord(
        val radioType: String,
        val registered: String,
        val mcc: String,
        val mnc: String,
        val areaCode: String,
        val rawCellId: String,
        val uniqueId: String,
        val siteId: String,
        val sectorId: String,
        val pciPsc: String,
        val channel: String,
        val signalDbm: String,
        val signalLevel: String,
        val timingAdvance: String,
        val operator: String,
        val countryIso: String,
        val timestampNanos: String
    ) {
        fun toJson(): JSONObject {
            return JSONObject().apply {
                put("radioType", radioType)
                put("registered", registered)
                put("mcc", mcc)
                put("mnc", mnc)
                put("areaCode", areaCode)
                put("rawCellId", rawCellId)
                put("uniqueId", uniqueId)
                put("siteId", siteId)
                put("sectorId", sectorId)
                put("pciPsc", pciPsc)
                put("channel", channel)
                put("signalDbm", signalDbm)
                put("signalLevel", signalLevel)
                put("timingAdvance", timingAdvance)
                put("operator", operator)
                put("countryIso", countryIso)
                put("timestampNanos", timestampNanos)
            }
        }
    }

    data class NetworkSnapshot(
        val timestamp: String,
        val activeTransport: String,
        val localIp: String,
        val networkValidated: Boolean,
        val internetCapability: Boolean,
        val metered: Boolean,
        val downstreamKbps: Int,
        val upstreamKbps: Int,
        val publicIp: PublicIpSnapshot,
        val location: LocationSnapshot,
        val wifi: WifiSnapshot?,
        val cellEnvironment: CellEnvironment,
        val servingCell: CellRecord?,
        val cells: List<CellRecord>
    ) {
        fun toJson(): JSONObject {
            val cellsArray = JSONArray()
            cells.forEach { cellsArray.put(it.toJson()) }

            return JSONObject().apply {
                put("timestamp", timestamp)
                put("activeTransport", activeTransport)
                put("localIp", localIp)
                put("networkValidated", networkValidated)
                put("internetCapability", internetCapability)
                put("metered", metered)
                put("downstreamKbps", downstreamKbps)
                put("upstreamKbps", upstreamKbps)
                put("publicIp", publicIp.toJson())
                put("location", location.toJson())
                put("wifi", wifi?.toJson() ?: JSONObject.NULL)
                put("cellEnvironment", cellEnvironment.toJson())
                put("servingCell", servingCell?.toJson() ?: JSONObject.NULL)
                put("cells", cellsArray)
            }
        }
    }
}
