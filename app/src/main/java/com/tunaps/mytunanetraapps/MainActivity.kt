package com.tunaps.mytunanetraapps

import androidx.appcompat.app.AppCompatActivity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.KeyEvent
import android.Manifest
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tunaps.mytunanetraapps.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale


class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var textToSpeech: TextToSpeech
    private var galleryRequestCode = 1
    private var mlTextRecognizer: TextRecognizer? = null
    private val PERMISSION_REQUEST_CODE = 100
    private var lastRecognizedText: String? = null
    private lateinit var binding : ActivityMainBinding

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        textToSpeech = TextToSpeech(this, this)

        checkAndRequestPermissions()
        // Initialize Firebase ML Kit Text Recognizer
        mlTextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private fun checkAndRequestPermissions() {
        val cameraPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        )
        val readStoragePermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_EXTERNAL_STORAGE
        )

        val permissionsToRequest = ArrayList<String>()

        if (cameraPermission != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.CAMERA)
        }
        if (readStoragePermission != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            // Check if all requested permissions are granted
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                // Permissions granted, you can proceed with your logic
            } else {
                // Some or all permissions were denied. Handle accordingly.
                // You might want to show a message to the user or take appropriate action.
            }
        }
    }

    private fun hasCameraPermission(): Boolean {
        val cameraPermission = Manifest.permission.CAMERA
        val permissionCheck = ContextCompat.checkSelfPermission(this, cameraPermission)
        return permissionCheck == PackageManager.PERMISSION_GRANTED
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                // Volume up button pressed
                if (hasCameraPermission()){
                    startTakePhoto()
                }else{
                    showPermissionDialog()
                }
                return true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                // Volume down button pressed
                replayTextToSpeech()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun startTakePhoto() {
        setupPermission()
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        intent.resolveActivity(packageManager)

        createCustomTempFile(application).also {
            val photoURI: Uri = FileProvider.getUriForFile(
                this@MainActivity,
                BuildConfig.APPLICATION_ID,
                it
            )
            currentPhotoPath = it.absolutePath
            intent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI)
            launcherIntentCamera.launch(intent)
        }
    }
    private  var currentPhotoPath: String? = null
    private val launcherIntentCamera = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (it.resultCode == RESULT_OK) {

            val myFile = File(currentPhotoPath)
            rotateImageIfRequired(myFile.absolutePath)
            val uriFIle = Uri.fromFile(myFile)
            performTextRecognition(uriFIle)
            binding.imageView.setImageURI(uriFIle)
//            viewModel.photoFIle = myFile
//            rotateImageIfRequired(myFile.absolutePath)
//            val result = BitmapFactory.decodeFile(myFile.path)
//            binding.tvFile.gone()
//            binding.ivAddPhoto.visible()
//            Glide
//                .with(this)
//                .load(result)
//                .into(binding.ivAddPhoto)

        }
    }

    private fun startGalleryActivity() {
        // Check permissions before starting the gallery activity
        // (You need to handle the permission checks)
        // For simplicity, we assume the required permissions are granted.

        val galleryIntent = Intent(Intent.ACTION_PICK)
        galleryIntent.type = "image/*"

        startActivityForResult(galleryIntent, galleryRequestCode)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == galleryRequestCode && resultCode == RESULT_OK) {
            // Image selected from the gallery
            val selectedImage = data?.data
            binding.imageView.setImageURI(selectedImage)

            // Perform ML Kit Text Recognition
            performTextRecognition(selectedImage)
        }
    }

    private fun performTextRecognition(imageUri: Any?) {
        val image = InputImage.fromFilePath(this, imageUri as android.net.Uri)
        binding.progressBar.visibility = View.VISIBLE
        mlTextRecognizer?.process(image)
            ?.addOnSuccessListener { visionText ->
                // Process the recognized text
                processRecognizedText(visionText)

                binding.progressBar.visibility = View.GONE
            }
            ?.addOnFailureListener { e ->
                Log.e("TextRecognition", "Text recognition failed: $e")
            }
    }

    private fun processRecognizedText(visionText: Text) {
        // Extract and display the recognized text
        val resultText = visionText.text
        binding.resultTextView.text = resultText
        lastRecognizedText = resultText

        // Read the recognized text using text-to-speech
        textToSpeech.speak(resultText, TextToSpeech.QUEUE_FLUSH, null, "")
    }

    private fun replayTextToSpeech() {
        if (lastRecognizedText != null) {
            // Replay the recognized text using text-to-speech
            textToSpeech.speak(lastRecognizedText, TextToSpeech.QUEUE_FLUSH, null, null)
        } else {
            // Handle the case where there's no recognized text to replay
            Log.d("TextToSpeech", "No recognized text to replay")
        }
    }


    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            // Configure text-to-speech settings, if needed
            val result = textToSpeech!!.setLanguage(Locale.US)

            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("TTS","The Language specified is not supported!")
            } else {
//                buttonSpeak!!.isEnabled = true
            }

        } else {
            Log.e("TextToSpeech", "Initialization failed")
        }
    }



    override fun onDestroy() {
        // Release the resources of the TextToSpeech engine
        textToSpeech.stop()
        textToSpeech.shutdown()
        super.onDestroy()
    }
    private fun setupPermission() {
        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(
                this,
                REQUIRED_PERMISSIONS,
                REQUEST_CODE_PERMISSIONS
            )
        }
    }

    private fun showPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("Izin Kamera Diperlukan")
            .setMessage("Aplikasi ini memerlukan izin kamera untuk berfungsi dengan baik. Mohon berikan izin tersebut di pengaturan aplikasi.")
            .setPositiveButton("Buka Pengaturan") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                val uri = Uri.fromParts("package", packageName, null)
                intent.data = uri
                startActivity(intent)
            }
            .setNegativeButton("Batal") { _, _ ->
                // Handle cancellation, if needed
            }
            .show()
    }

    companion object {

        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        private const val REQUEST_CODE_PERMISSIONS = 10

    }
}