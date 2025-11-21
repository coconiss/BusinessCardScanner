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
import android.util.Size
import android.view.LayoutInflater
import android.view.MotionEvent
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
    private var camera: Camera? = null
    private lateinit var cameraProvider: ProcessCameraProvider
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
            cameraProvider = cameraProviderFuture.get()

            // Preview 설정
            val preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .build()
                .also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }

            // ImageCapture 설정 (개선: 고해상도, 고품질)
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setTargetResolution(Size(1920, 1080)) // HD 해상도
                .setTargetRotation(binding.previewView.display.rotation)
                .setFlashMode(ImageCapture.FLASH_MODE_AUTO) // 자동 플래시
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // 기존 바인딩 해제
                cameraProvider.unbindAll()

                // 카메라와 use case 바인딩
                camera = cameraProvider.bindToLifecycle(
                    viewLifecycleOwner,
                    cameraSelector,
                    preview,
                    imageCapture
                )

                // 터치 포커스 설정
                setupTouchFocus()

            } catch (e: Exception) {
                Log.e("CameraFragment", "카메라 시작 실패", e)
                Toast.makeText(requireContext(), "카메라 시작 실패: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    /**
     * 터치 포커스 기능 설정
     */
    private fun setupTouchFocus() {
        binding.previewView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    return@setOnTouchListener true
                }
                MotionEvent.ACTION_UP -> {
                    val factory = binding.previewView.meteringPointFactory
                    val point = factory.createPoint(event.x, event.y)

                    // 포커스 및 노출 측정 액션 생성
                    val action = FocusMeteringAction.Builder(point)
                        .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
                        .build()

                    // 포커스 실행
                    camera?.cameraControl?.startFocusAndMetering(action)?.addListener({
                        Log.d("CameraFragment", "포커스 완료")
                    }, ContextCompat.getMainExecutor(requireContext()))

                    // 포커스 인디케이터 표시 (선택사항)
                    showFocusIndicator(event.x, event.y)

                    return@setOnTouchListener true
                }
            }
            false
        }
    }

    /**
     * 포커스 인디케이터 표시 (선택사항 - UI 피드백)
     */
    private fun showFocusIndicator(x: Float, y: Float) {
        // 여기에 포커스 링 애니메이션 추가 가능
        Log.d("CameraFragment", "포커스 위치: ($x, $y)")
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        // 촬영 전 포커스 트리거 (중앙)
        val centerX = binding.previewView.width / 2f
        val centerY = binding.previewView.height / 2f
        val factory = binding.previewView.meteringPointFactory
        val centerPoint = factory.createPoint(centerX, centerY)
        val focusAction = FocusMeteringAction.Builder(centerPoint).build()

        camera?.cameraControl?.startFocusAndMetering(focusAction)

        val photoFile = File(
            requireContext().cacheDir,
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                .format(System.currentTimeMillis()) + ".jpg"
        )

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        binding.progressBar.visibility = View.VISIBLE
        binding.btnCapture.isEnabled = false
        binding.btnGallery.isEnabled = false
        binding.statusText.visibility = View.VISIBLE
        binding.statusText.text = "촬영 중..."

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Log.d("CameraFragment", "사진 저장 성공: ${photoFile.absolutePath}")
                    processImageFile(photoFile)
                }

                override fun onError(exc: ImageCaptureException) {
                    Log.e("CameraFragment", "촬영 실패", exc)
                    binding.progressBar.visibility = View.GONE
                    binding.btnCapture.isEnabled = true
                    binding.btnGallery.isEnabled = true
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

                // EXIF 정보로 회전 각도 확인
                val exif = ExifInterface(file.absolutePath)
                val orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
                val rotationDegrees = when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }

                // 이미지 로딩 (메모리 효율적으로)
                val bitmap = decodeSampledBitmapFromFile(file.absolutePath, 2048, 2048)
                val rotatedBitmap = if (rotationDegrees != 0f) {
                    rotateBitmap(bitmap, rotationDegrees)
                } else {
                    bitmap
                }

                withContext(Dispatchers.Main) {
                    binding.statusText.text = "명함 영역 자르는 중..."
                }

                // 가이드라인 영역으로 크롭
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

                // OCR 수행
                val text = textRecognizer.recognizeText(preprocessedBitmap)
                Log.d("OcrResult", "Recognized Text: ${text.text}")
                Log.d("OcrResult", "Text blocks: ${text.textBlocks.size}")

                withContext(Dispatchers.Main) {
                    binding.statusText.text = "정보 추출 중..."
                }

                // 연락처 정보 파싱
                val contact = contactParser.parseContact(text)
                contact.imageUri = file.absolutePath

                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.btnCapture.isEnabled = true
                    binding.btnGallery.isEnabled = true
                    binding.statusText.visibility = View.GONE

                    if (text.text.isNotEmpty()) {
                        navigateToEditContact(contact)
                    } else {
                        Toast.makeText(
                            requireContext(),
                            "텍스트를 인식할 수 없습니다. 조명을 확인하고 다시 촬영해주세요.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

                // 메모리 해제
                if (bitmap != rotatedBitmap) bitmap.recycle()
                rotatedBitmap.recycle()
                croppedBitmap.recycle()
                preprocessedBitmap.recycle()

            } catch (e: Exception) {
                Log.e("ProcessError", "이미지 처리 실패", e)
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.btnCapture.isEnabled = true
                    binding.btnGallery.isEnabled = true
                    binding.statusText.visibility = View.GONE
                    Toast.makeText(
                        requireContext(),
                        "처리 실패: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun processImageUri(uri: Uri) {
        binding.progressBar.visibility = View.VISIBLE
        binding.btnCapture.isEnabled = false
        binding.btnGallery.isEnabled = false
        binding.statusText.visibility = View.VISIBLE
        binding.statusText.text = "이미지 로딩 중..."

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val inputStream = requireContext().contentResolver.openInputStream(uri)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()

                if (bitmap == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "이미지를 불러올 수 없습니다", Toast.LENGTH_SHORT).show()
                        binding.progressBar.visibility = View.GONE
                        binding.btnCapture.isEnabled = true
                        binding.btnGallery.isEnabled = true
                        binding.statusText.visibility = View.GONE
                    }
                    return@launch
                }

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
                    binding.btnCapture.isEnabled = true
                    binding.btnGallery.isEnabled = true
                    binding.statusText.visibility = View.GONE

                    if (text.text.isNotEmpty()) {
                        navigateToEditContact(contact)
                    } else {
                        Toast.makeText(
                            requireContext(),
                            "텍스트를 인식할 수 없습니다",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                // 메모리 해제
                bitmap.recycle()
                preprocessedBitmap.recycle()

            } catch (e: Exception) {
                Log.e("ProcessError", "이미지 처리 실패", e)
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.btnCapture.isEnabled = true
                    binding.btnGallery.isEnabled = true
                    binding.statusText.visibility = View.GONE
                    Toast.makeText(
                        requireContext(),
                        "처리 실패: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    /**
     * 메모리 효율적인 비트맵 디코딩
     */
    private fun decodeSampledBitmapFromFile(
        path: String,
        reqWidth: Int,
        reqHeight: Int
    ): Bitmap {
        return BitmapFactory.Options().run {
            inJustDecodeBounds = true
            BitmapFactory.decodeFile(path, this)

            // 샘플 크기 계산
            inSampleSize = calculateInSampleSize(this, reqWidth, reqHeight)

            inJustDecodeBounds = false
            BitmapFactory.decodeFile(path, this)
        }
    }

    /**
     * 적절한 샘플 크기 계산
     */
    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val (height: Int, width: Int) = options.run { outHeight to outWidth }
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2

            while (halfHeight / inSampleSize >= reqHeight &&
                halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }

        return inSampleSize
    }

    /**
     * 가이드라인 영역으로 이미지 크롭
     */
    private fun cropImage(bitmap: Bitmap, cropRect: RectF): Bitmap {
        val imageWidth = bitmap.width.toFloat()
        val imageHeight = bitmap.height.toFloat()

        val viewWidth = binding.previewView.width.toFloat()
        val viewHeight = binding.previewView.height.toFloat()

        // 이미지가 뷰에 맞춰진 스케일 계산
        val scale = max(viewWidth / imageWidth, viewHeight / imageHeight)

        // 이미지가 뷰 중앙에 배치되었을 때의 오프셋 계산
        val dx = (viewWidth - imageWidth * scale) / 2f
        val dy = (viewHeight - imageHeight * scale) / 2f

        // 뷰 좌표를 이미지 좌표로 변환
        val imageCropLeft = ((cropRect.left - dx) / scale).toInt().coerceAtLeast(0)
        val imageCropTop = ((cropRect.top - dy) / scale).toInt().coerceAtLeast(0)
        val imageCropWidth = (cropRect.width() / scale).toInt()
            .coerceAtMost(bitmap.width - imageCropLeft)
        val imageCropHeight = (cropRect.height() / scale).toInt()
            .coerceAtMost(bitmap.height - imageCropTop)

        return Bitmap.createBitmap(
            bitmap,
            imageCropLeft,
            imageCropTop,
            imageCropWidth,
            imageCropHeight
        )
    }

    /**
     * 비트맵 회전
     */
    private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        if (degrees == 0f) return bitmap

        val matrix = Matrix()
        matrix.postRotate(degrees)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * 편집 화면으로 이동
     */
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