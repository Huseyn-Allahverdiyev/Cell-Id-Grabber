package com.project.cellidgrabber

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.telephony.*
import android.util.Log
import android.widget.Button
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {

    private lateinit var telephonyManager: TelephonyManager
    private lateinit var cellTowerTable: TableLayout
    private lateinit var startStopButton: Button
    private lateinit var clearButton: Button
    private lateinit var saveButton: Button
    private lateinit var contentTextView: TextView

    private val cellTowerData = mutableListOf<CellTower>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        contentTextView = findViewById(R.id.content)
        cellTowerTable = findViewById(R.id.cellTowerTable)
        startStopButton = findViewById(R.id.startStopButton)
        clearButton = findViewById(R.id.clearButton)
        saveButton = findViewById(R.id.saveButton)

        telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager

        checkPermissions()

        startStopButton.setOnClickListener { getCellTowerInfo() }
        clearButton.setOnClickListener { clearData() }
        saveButton.setOnClickListener { saveData() }

        updateTable()
    }

    private fun checkPermissions() {
        val permissions = arrayOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        ActivityCompat.requestPermissions(this, permissions, 101)
    }

    private fun clearData() {
        cellTowerData.clear()
        updateTable()
    }

    private fun saveData() {
        val fileName = "aky_network_data_${System.currentTimeMillis()}.txt"

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS)
        }

        val uri = contentResolver.insert(MediaStore.Files.getContentUri("external"), values)

        uri?.let {
            contentResolver.openOutputStream(it)?.use { stream ->
                cellTowerData.forEach { tower ->
                    stream.write(tower.toString().toByteArray())
                    stream.write("\n\n".toByteArray())
                }
            }
            contentTextView.text = "Saved: $fileName"
        }
    }

    private fun getCellTowerInfo() {
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) return

        updateCellTowerData(telephonyManager.allCellInfo)
    }

    private fun updateCellTowerData(cellInfoList: List<CellInfo>) {
        cellTowerData.clear()

        val networkType = getNetworkTypeName(telephonyManager.dataNetworkType)
        val simState = getSimStateName(telephonyManager.simState)
        val dataState = getDataStateName(telephonyManager.dataState)
        val operatorName = telephonyManager.networkOperatorName
        val operatorCode = telephonyManager.networkOperator
        val countryIso = telephonyManager.networkCountryIso
        val roaming = telephonyManager.isNetworkRoaming
        val localIp = getLocalIpAddress()

        for (cell in cellInfoList) {

            when (cell) {

                is CellInfoLte -> {
                    val id = cell.cellIdentity
                    val signal = cell.cellSignalStrength

                    cellTowerData.add(
                        CellTower(
                            mcc = id.mccString ?: "N/A",
                            mnc = id.mncString ?: "N/A",
                            lacTac = id.tac.toString(),
                            cid = id.ci.toString(),
                            pci = id.pci.toString(),
                            arfcn = id.earfcn.toString(),
                            signalDbm = signal.dbm.toString(),
                            signalLevel = signal.level.toString(),
                            networkType = networkType,
                            simState = simState,
                            dataState = dataState,
                            operatorName = operatorName,
                            operatorCode = operatorCode,
                            countryIso = countryIso,
                            roaming = roaming.toString(),
                            localIp = localIp,
                            registered = cell.isRegistered.toString()
                        )
                    )
                }

                is CellInfoGsm -> {
                    val id = cell.cellIdentity
                    val signal = cell.cellSignalStrength

                    cellTowerData.add(
                        CellTower(
                            mcc = id.mccString ?: "N/A",
                            mnc = id.mncString ?: "N/A",
                            lacTac = id.lac.toString(),
                            cid = id.cid.toString(),
                            pci = "N/A",
                            arfcn = id.arfcn.toString(),
                            signalDbm = signal.dbm.toString(),
                            signalLevel = signal.level.toString(),
                            networkType = networkType,
                            simState = simState,
                            dataState = dataState,
                            operatorName = operatorName,
                            operatorCode = operatorCode,
                            countryIso = countryIso,
                            roaming = roaming.toString(),
                            localIp = localIp,
                            registered = cell.isRegistered.toString()
                        )
                    )
                }
            }
        }

        updateTable()
    }

    private fun updateTable() {
        cellTowerTable.removeViews(1, maxOf(0, cellTowerTable.childCount - 1))

        for ((index, tower) in cellTowerData.withIndex()) {
            addRow("${index + 1}", tower)
        }
    }

    private fun addRow(index: String, tower: CellTower) {
        val row = TableRow(this)

        fun cell(text: String): TextView {
            return TextView(this).apply {
                this.text = text
                setPadding(8, 8, 8, 8)
                setBackgroundResource(R.drawable.cell_border)
            }
        }

        row.addView(cell(index))
        row.addView(cell(tower.mcc))
        row.addView(cell(tower.mnc))
        row.addView(cell(tower.lacTac))
        row.addView(cell(tower.cid))
        row.addView(cell(tower.pci))
        row.addView(cell(tower.arfcn))
        row.addView(cell("${tower.signalDbm} dBm"))
        row.addView(cell(tower.networkType))
        row.addView(cell(tower.operatorName))

        row.addView(cell(tower.operatorCode))
        row.addView(cell(tower.localIp))
        row.addView(cell(tower.simState))
        row.addView(cell(tower.dataState))
        row.addView(cell(tower.registered))
        row.addView(cell(tower.signalLevel))

        cellTowerTable.addView(row)
    }

    private fun getLocalIpAddress(): String {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                ?.hostAddress ?: "N/A"
        } catch (e: Exception) {
            "N/A"
        }
    }

    private fun getNetworkTypeName(type: Int): String = when (type) {
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
        TelephonyManager.NETWORK_TYPE_NR -> "5G"
        TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPA+"
        TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
        TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
        else -> "UNKNOWN"
    }

    private fun getSimStateName(state: Int): String = when (state) {
        TelephonyManager.SIM_STATE_READY -> "READY"
        TelephonyManager.SIM_STATE_ABSENT -> "ABSENT"
        else -> "OTHER"
    }

    private fun getDataStateName(state: Int): String = when (state) {
        TelephonyManager.DATA_CONNECTED -> "CONNECTED"
        TelephonyManager.DATA_CONNECTING -> "CONNECTING"
        TelephonyManager.DATA_DISCONNECTED -> "DISCONNECTED"
        else -> "UNKNOWN"
    }

    data class CellTower(
        val mcc: String,
        val mnc: String,
        val lacTac: String,
        val cid: String,
        val pci: String,
        val arfcn: String,
        val signalDbm: String,
        val signalLevel: String,
        val networkType: String,
        val simState: String,
        val dataState: String,
        val operatorName: String,
        val operatorCode: String,
        val countryIso: String,
        val roaming: String,
        val localIp: String,
        val registered: String
    )
}
