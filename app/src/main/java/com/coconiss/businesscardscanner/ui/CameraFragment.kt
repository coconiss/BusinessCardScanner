package com.coconiss.businesscardscanner.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.coconiss.businesscardscanner.R
import com.coconiss.businesscardscanner.data.Contact
import com.coconiss.businesscardscanner.databinding.FragmentCameraBinding
import com.coconiss.businesscardscanner.ocr.ContactParser
import com.coconiss.businesscardscanner.ocr.ImagePreprocessor
import com.coconiss.businesscardscanner.ocr.TextRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

class CameraFragment : Fragment() {

    private var _binding: FragmentCameraBinding? = null
    private val binding get() = _binding!!

    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var textRecognizer: TextRecognizer
    private lateinit var contactParser: ContactParser
    private lateinit var imagePreprocessor: ImagePreprocessor

    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCamera()
        } else {
            Toast.makeText(requireContext(), "카메라 권한이 필요합니다", Toast.LENGTH_SHORT).show()
            findNavController().navigateUp()
        }
    }

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            processImageUri(it)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCameraBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // OpenCV 초기화
        if (!OpenCVLoader.initDebug()) {
            Log.e("OpenCV", "OpenCV 초기화 실패")
            Toast.makeText(requireContext(), "이미지 처리 라이브러리 초기화 실패", Toast.LENGTH_SHORT).show()
        } else {
            Log.d("OpenCV", "OpenCV 초기화 성공")
        }

        textRecognizer = TextRecognizer()
        contactParser = ContactParser()
        imagePreprocessor = ImagePreprocessor()
        cameraExecutor = Executors.newSingleThreadExecutor()

        binding.btnCapture.setOnClickListener {
            takePhoto()
        }

        binding.btnGallery.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        binding.btnCancel.setOnClickListener {
            findNavController().navigateUp()
        }

        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setTargetRotation(binding.previewView.display.rotation)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    viewLifecycleOwner,
                    cameraSelector,
                    preview,
                    imageCapture
                )
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "카메라 시작 실패: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        val photoFile = File(
            requireContext().cacheDir,
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                .format(System.currentTimeMillis()) + ".jpg"
        )

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        binding.progressBar.visibility = View.VISIBLE
        binding.btnCapture.isEnabled = false
        binding.statusText.visibility = View.VISIBLE
        binding.statusText.text = "촬영 중..."

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    processImageFile(photoFile)
                }

                override fun onError(exc: ImageCaptureException) {
                    binding.progressBar.visibility = View.GONE
                    binding.btnCapture.isEnabled = true
                    binding.statusText.visibility = View.GONE
                    Toast.makeText(requireContext(), "촬영 실패: ${exc.message}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun processImageFile(file: File) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withContext(Dispatchers.Main) {
                    binding.statusText.text = "이미지 로딩 중..."
                }

                val exif = ExifInterface(file.absolutePath)
                val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                val rotationDegrees = when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }

                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                val rotatedBitmap = rotateBitmap(bitmap, rotationDegrees)

                withContext(Dispatchers.Main) {
                    binding.statusText.text = "명함 영역 자르는 중..."
                }

                val guidelineRect = binding.guidelineView.getGuidelineRect()
                val croppedBitmap = cropImage(rotatedBitmap, guidelineRect)

                withContext(Dispatchers.Main) {
                    binding.statusText.text = "이미지 전처리 중..."
                }

                // 이미지 전처리 적용
                val preprocessedBitmap = try {
                    imagePreprocessor.preprocessBusinessCard(croppedBitmap)
                } catch (e: Exception) {
                    Log.e("Preprocessing", "전처리 실패, 간단한 전처리 사용: ${e.message}")
                    imagePreprocessor.simplePreprocess(croppedBitmap)
                }

                // 전처리된 이미지를 파일로 저장
                val preprocessedFile = File(
                    requireContext().cacheDir,
                    "preprocessed_${file.name}"
                )
                preprocessedFile.outputStream().use { output ->
                    preprocessedBitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)
                }

                withContext(Dispatchers.Main) {
                    binding.statusText.text = "텍스트 인식 중..."
                }

                val text = textRecognizer.recognizeText(preprocessedBitmap)
                Log.d("OcrResult", "Recognized Text: ${text.text}")

                withContext(Dispatchers.Main) {
                    binding.statusText.text = "정보 추출 중..."
                }

                val contact = contactParser.parseContact(text)
                contact.imageUri = file.absolutePath

                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.btnCapture.isEnabled = true
                    binding.statusText.visibility = View.GONE

                    if (text.text.isNotEmpty()) {
                        navigateToEditContact(contact)
                    } else {
                        Toast.makeText(requireContext(), "텍스트를 인식할 수 없습니다", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.btnCapture.isEnabled = true
                    binding.statusText.visibility = View.GONE
                    Toast.makeText(requireContext(), "처리 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                    Log.e("ProcessError", "Error: ${e.stackTraceToString()}")
                }
            }
        }
    }

    private fun processImageUri(uri: Uri) {
        binding.progressBar.visibility = View.VISIBLE
        binding.statusText.visibility = View.VISIBLE
        binding.statusText.text = "이미지 로딩 중..."

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val inputStream = requireContext().contentResolver.openInputStream(uri)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()

                withContext(Dispatchers.Main) {
                    binding.statusText.text = "이미지 전처리 중..."
                }

                // 이미지 전처리
                val preprocessedBitmap = try {
                    imagePreprocessor.preprocessBusinessCard(bitmap)
                } catch (e: Exception) {
                    Log.e("Preprocessing", "전처리 실패, 간단한 전처리 사용: ${e.message}")
                    imagePreprocessor.simplePreprocess(bitmap)
                }

                val photoFile = File(
                    requireContext().cacheDir,
                    SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                        .format(System.currentTimeMillis()) + ".jpg"
                )

                photoFile.outputStream().use { output ->
                    preprocessedBitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)
                }

                withContext(Dispatchers.Main) {
                    binding.statusText.text = "텍스트 인식 중..."
                }

                val text = textRecognizer.recognizeText(preprocessedBitmap)
                Log.d("OcrResult", "Recognized Text: ${text.text}")

                withContext(Dispatchers.Main) {
                    binding.statusText.text = "정보 추출 중..."
                }

                val contact = contactParser.parseContact(text)
                contact.imageUri = photoFile.absolutePath

                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.statusText.visibility = View.GONE

                    if (text.text.isNotEmpty()) {
                        navigateToEditContact(contact)
                    } else {
                        Toast.makeText(requireContext(), "텍스트를 인식할 수 없습니다", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.statusText.visibility = View.GONE
                    Toast.makeText(requireContext(), "처리 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                    Log.e("ProcessError", "Error: ${e.stackTraceToString()}")
                }
            }
        }
    }

    private fun cropImage(bitmap: Bitmap, cropRect: RectF): Bitmap {
        val imageWidth = bitmap.width.toFloat()
        val imageHeight = bitmap.height.toFloat()

        val viewWidth = binding.previewView.width.toFloat()
        val viewHeight = binding.previewView.height.toFloat()

        val scale = max(viewWidth / imageWidth, viewHeight / imageHeight)

        val dx = (viewWidth - imageWidth * scale) / 2f
        val dy = (viewHeight - imageHeight * scale) / 2f

        val imageCropLeft = ((cropRect.left - dx) / scale).toInt().coerceAtLeast(0)
        val imageCropTop = ((cropRect.top - dy) / scale).toInt().coerceAtLeast(0)
        val imageCropWidth = (cropRect.width() / scale).toInt().coerceAtMost(bitmap.width - imageCropLeft)
        val imageCropHeight = (cropRect.height() / scale).toInt().coerceAtMost(bitmap.height - imageCropTop)

        return Bitmap.createBitmap(
            bitmap,
            imageCropLeft,
            imageCropTop,
            imageCropWidth,
            imageCropHeight
        )
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        if (degrees == 0f) return bitmap

        val matrix = Matrix()
        matrix.postRotate(degrees)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun navigateToEditContact(contact: Contact) {
        val bundle = bundleOf("contact" to contact)
        findNavController().navigate(R.id.action_camera_to_editContact, bundle)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cameraExecutor.shutdown()
        textRecognizer.close()
        _binding = null
    }
}