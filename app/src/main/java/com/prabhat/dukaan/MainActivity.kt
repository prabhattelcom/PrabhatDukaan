
package com.prabhat.dukaan

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.ValueCallback
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import java.io.ByteArrayOutputStream
import java.util.UUID

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var galleryLauncher: ActivityResultLauncher<String>
    private lateinit var bluetoothPermissionLauncher: ActivityResultLauncher<String>

    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPrintImage: String? = null

    private val printerUUID: UUID = UUID.fromString(
        "00001101-0000-1000-8000-00805F9B34FB"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Gallery launcher
        galleryLauncher = registerForActivityResult(
            ActivityResultContracts.GetContent()
        ) { uri: Uri? ->

            fileUploadCallback?.onReceiveValue(
                if (uri != null) arrayOf(uri) else null
            )

            fileUploadCallback = null
        }

        // Bluetooth permission launcher
        bluetoothPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->

            if (granted) {
                showPairedPrinters()
            } else {
                pendingPrintImage = null
                Toast.makeText(
                    this,
                    "Bluetooth permission नहीं मिली",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        webView = WebView(this)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.allowContentAccess = true

        webView.webViewClient = WebViewClient()

        // JavaScript से Bluetooth printer चलाने का bridge
        webView.addJavascriptInterface(
            PrinterBridge(),
            "AndroidPrinter"
        )

        // Gallery support
        webView.webChromeClient = object : WebChromeClient() {

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {

                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback

                return try {
                    galleryLauncher.launch("image/*")
                    true
                } catch (e: Exception) {
                    fileUploadCallback?.onReceiveValue(null)
                    fileUploadCallback = null
                    false
                }
            }
        }

        webView.loadUrl("file:///android_asset/index.html")

        setContentView(webView)
    }

    // JavaScript bridge
    inner class PrinterBridge {

        @JavascriptInterface
        fun printImage(base64Image: String) {

            runOnUiThread {
                pendingPrintImage = base64Image
                requestBluetoothPermission()
            }
        }
    }

    // Bluetooth permission
    private fun requestBluetoothPermission() {

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            bluetoothPermissionLauncher.launch(
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            showPairedPrinters()
        }
    }

    // Show paired Bluetooth devices
    @SuppressLint("MissingPermission")
    private fun showPairedPrinters() {

        try {
            val adapter = BluetoothAdapter.getDefaultAdapter()

            if (adapter == null) {
                Toast.makeText(
                    this,
                    "इस मोबाइल में Bluetooth उपलब्ध नहीं है",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            if (!adapter.isEnabled) {
                Toast.makeText(
                    this,
                    "पहले मोबाइल का Bluetooth चालू करें",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            val devices = adapter.bondedDevices.toList()

            if (devices.isEmpty()) {
                Toast.makeText(
                    this,
                    "पहले Printer को मोबाइल Bluetooth में Pair करें",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            val names = devices.map {
                "${it.name ?: "Bluetooth Device"} (${it.address})"
            }.toTypedArray()

            AlertDialog.Builder(this)
                .setTitle("अपना Thermal Printer चुनें")
                .setItems(names) { _, position ->

                    val selectedDevice = devices[position]
                    val image = pendingPrintImage

                    if (image != null) {
                        connectAndPrint(selectedDevice, image)
                    }
                }
                .setNegativeButton("रद्द करें") { _, _ ->
                    pendingPrintImage = null
                }
                .show()

        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Bluetooth समस्या: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // Connect and print
    @SuppressLint("MissingPermission")
    private fun connectAndPrint(
        device: BluetoothDevice,
        base64Image: String
    ) {

        Toast.makeText(
            this,
            "Printer से कनेक्ट हो रहा है...",
            Toast.LENGTH_SHORT
        ).show()

        Thread {

            var socket: BluetoothSocket? = null

            try {
                // Standard Bluetooth Classic connection
                val firstSocket =
                    device.createRfcommSocketToServiceRecord(printerUUID)

                try {
                    firstSocket.connect()
                    socket = firstSocket
                } catch (firstError: Exception) {
                    try {
                        firstSocket.close()
                    } catch (_: Exception) {
                    }

                    // Alternate connection attempt
                    val secondSocket =
                        device.createInsecureRfcommSocketToServiceRecord(
                            printerUUID
                        )

                    secondSocket.connect()
                    socket = secondSocket
                }

                val cleanBase64 = base64Image.substringAfter(
                    ",",
                    base64Image
                )

                val imageBytes = Base64.decode(
                    cleanBase64,
                    Base64.DEFAULT
                )

                val bitmap = BitmapFactory.decodeByteArray(
                    imageBytes,
                    0,
                    imageBytes.size
                ) ?: throw Exception("बिल की फोटो नहीं खुली")

                val printData = convertBitmapToPrinterData(bitmap)

                val output = socket!!.outputStream

                // Printer initialize
                output.write(byteArrayOf(0x1B, 0x40))

                // Send image in small chunks
                var offset = 0

                while (offset < printData.size) {

                    val count = minOf(
                        4096,
                        printData.size - offset
                    )

                    output.write(printData, offset, count)
                    output.flush()

                    offset += count
                    Thread.sleep(30)
                }

                // Paper feed
                output.write(byteArrayOf(0x0A, 0x0A, 0x0A))
                output.flush()

                runOnUiThread {
                    Toast.makeText(
                        this,
                        "बिल Printer को भेज दिया गया!",
                        Toast.LENGTH_LONG
                    ).show()
                }

            } catch (e: Exception) {

                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Print Error: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }

            } finally {

                try {
                    socket?.close()
                } catch (_: Exception) {
                }

                pendingPrintImage = null
            }

        }.start()
    }

    // Convert bill image to 58mm ESC/POS raster format
    private fun convertBitmapToPrinterData(
        original: Bitmap
    ): ByteArray {

        val targetWidth = 384

        val targetHeight = (
            original.height.toFloat() *
            targetWidth / original.width
        ).toInt().coerceAtLeast(1)

        if (targetHeight > 65000) {
            throw Exception("बिल बहुत लंबा है")
        }

        val bitmap = Bitmap.createScaledBitmap(
            original,
            targetWidth,
            targetHeight,
            true
        )

        val widthBytes = (targetWidth + 7) / 8

        val output = ByteArrayOutputStream()

        // ESC/POS image command
        output.write(0x1D)
        output.write(0x76)
        output.write(0x30)
        output.write(0x00)

        output.write(widthBytes and 0xFF)
        output.write((widthBytes shr 8) and 0xFF)

        output.write(targetHeight and 0xFF)
        output.write((targetHeight shr 8) and 0xFF)

        for (y in 0 until targetHeight) {

            val row = ByteArray(widthBytes)

            for (x in 0 until targetWidth) {

                val pixel = bitmap.getPixel(x, y)

                val gray = (
                    Color.red(pixel) * 299 +
                    Color.green(pixel) * 587 +
                    Color.blue(pixel) * 114
                ) / 1000

                if (gray < 180) {

                    val byteIndex = x / 8
                    val bit = 0x80 shr (x % 8)

                    row[byteIndex] = (
                        row[byteIndex].toInt() or bit
                    ).toByte()
                }
            }

            output.write(row)
        }

        bitmap.recycle()

        return output.toByteArray()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {

        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {

        fileUploadCallback?.onReceiveValue(null)
        fileUploadCallback = null

        webView.removeJavascriptInterface("AndroidPrinter")
        webView.destroy()

        super.onDestroy()
    }
}
