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

class CameraFragment : Fragment() {

    private val TAG = "CameraFragment"

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

            val preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .build()
                .also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setTargetResolution(Size(1080, 1920))
                .setTargetRotation(binding.previewView.display.rotation)
                .setFlashMode(ImageCapture.FLASH_MODE_AUTO)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()

                camera = cameraProvider.bindToLifecycle(
                    viewLifecycleOwner,
                    cameraSelector,
                    preview,
                    imageCapture
                )

                setupTouchFocus()

            } catch (e: Exception) {
                Log.e("CameraFragment", "카메라 시작 실패", e)
                Toast.makeText(requireContext(), "카메라 시작 실패: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun setupTouchFocus() {
        binding.previewView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    return@setOnTouchListener true
                }
                MotionEvent.ACTION_UP -> {
                    val factory = binding.previewView.meteringPointFactory
                    val point = factory.createPoint(event.x, event.y)

                    val action = FocusMeteringAction.Builder(point)
                        .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
                        .build()

                    camera?.cameraControl?.startFocusAndMetering(action)?.addListener({
                        Log.d("CameraFragment", "포커스 완료")
                    }, ContextCompat.getMainExecutor(requireContext()))

                    showFocusIndicator(event.x, event.y)

                    return@setOnTouchListener true
                }
            }
            false
        }
    }

    private fun showFocusIndicator(x: Float, y: Float) {
        Log.d("CameraFragment", "포커스 위치: ($x, $y)")
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

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

                Log.d(TAG, "=== 이미지 처리 시작 ===")

                val bitmap = decodeSampledBitmapFromFile(file.absolutePath, 2048, 2048)

                Log.d(TAG, "로딩된 이미지 크기: ${bitmap.width} x ${bitmap.height}")

                withContext(Dispatchers.Main) {
                    binding.statusText.text = "명함 영역 자르는 중..."

                    val guidelineRect = binding.guidelineView.getGuidelineRect()
                    Log.d(TAG, "PreviewView 크기: ${binding.previewView.width} x ${binding.previewView.height}")
                }

                val guidelineRect = withContext(Dispatchers.Main) {
                    binding.guidelineView.getGuidelineRect()
                }
                val croppedBitmap = cropImage(bitmap, guidelineRect)

                Log.d(TAG, "크롭된 이미지 크기: ${croppedBitmap.width} x ${croppedBitmap.height}")

                val croppedFile = File(
                    requireContext().cacheDir,
                    "cropped_${file.name}"
                )
                croppedFile.outputStream().use { output ->
                    croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)
                }
                Log.d(TAG, "크롭된 이미지 저장: ${croppedFile.absolutePath}")

                withContext(Dispatchers.Main) {
                    binding.statusText.text = "이미지 전처리 중..."
                }

                //val preprocessedBitmap = try {
                //    imagePreprocessor.minimalPreprocess(croppedBitmap)
                //} catch (e: Exception) {
                //    Log.e("Preprocessing", "전처리 실패, 간단한 전처리 사용: ${e.message}")
                //    imagePreprocessor.simplePreprocess(croppedBitmap)
                //}

                // 옵션 2: 자동 명함 검출 켜기 (기존)
                val preprocessedBitmap = try {
                    imagePreprocessor.preprocessBusinessCard(croppedBitmap, enableWarp = true)
                } catch (e: Exception) {
                    Log.e("Preprocessing", "전처리 실패: ${e.message}")
                    imagePreprocessor.minimalPreprocess(croppedBitmap)
                }

                Log.d(TAG, "전처리된 이미지 크기: ${preprocessedBitmap.width} x ${preprocessedBitmap.height}")

                val preprocessedFile = File(
                    requireContext().cacheDir,
                    "preprocessed_${file.name}"
                )
                preprocessedFile.outputStream().use { output ->
                    preprocessedBitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)
                }
                Log.d(TAG, "전처리된 이미지 저장: ${preprocessedFile.absolutePath}")

                withContext(Dispatchers.Main) {
                    binding.statusText.text = "텍스트 인식 중..."
                }

                val text = textRecognizer.recognizeText(preprocessedBitmap)
                Log.d(TAG, "인식된 텍스트: ${text.text}")
                Log.d(TAG, "텍스트 블록 수: ${text.textBlocks.size}")

                withContext(Dispatchers.Main) {
                    binding.statusText.text = "정보 추출 중..."
                }

                val contact = contactParser.parseContact(text)
                contact.imageUri = file.absolutePath

                Log.d(TAG, "=== 이미지 처리 완료 ===")

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

                bitmap.recycle()
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
     * 메모리 효율적인 비트맵 디코딩 + EXIF 회전 처리
     */
    private fun decodeSampledBitmapFromFile(
        path: String,
        reqWidth: Int,
        reqHeight: Int
    ): Bitmap {
        val bitmap = BitmapFactory.Options().run {
            inJustDecodeBounds = true
            BitmapFactory.decodeFile(path, this)
            inSampleSize = calculateInSampleSize(this, reqWidth, reqHeight)
            inJustDecodeBounds = false
            BitmapFactory.decodeFile(path, this)
        }

        Log.d(TAG, "디코딩된 비트맵 크기 (회전 전): ${bitmap.width} x ${bitmap.height}")

        return rotateBitmapIfRequired(bitmap, path)
    }

    /**
     * EXIF 정보에 따라 비트맵 회전
     */
    private fun rotateBitmapIfRequired(bitmap: Bitmap, imagePath: String): Bitmap {
        try {
            val exif = ExifInterface(imagePath)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )

            Log.d(TAG, "EXIF Orientation 값: $orientation")

            val rotationDegrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                ExifInterface.ORIENTATION_NORMAL -> 0f
                else -> 0f
            }

            if (rotationDegrees == 0f) {
                Log.d(TAG, "회전 불필요 (EXIF orientation: $orientation)")
                return bitmap
            }

            Log.d(TAG, "이미지 회전 적용: ${rotationDegrees}도")

            val matrix = Matrix()
            matrix.postRotate(rotationDegrees)

            val rotatedBitmap = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
            )

            if (rotatedBitmap != bitmap) {
                bitmap.recycle()
            }

            Log.d(TAG, "회전 후 이미지 크기: ${rotatedBitmap.width} x ${rotatedBitmap.height}")

            return rotatedBitmap

        } catch (e: Exception) {
            Log.e(TAG, "EXIF 회전 처리 실패: ${e.message}")
            e.printStackTrace()
            return bitmap
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
        try {
            val imageWidth = bitmap.width.toFloat()
            val imageHeight = bitmap.height.toFloat()

            val viewWidth = binding.previewView.width.toFloat()
            val viewHeight = binding.previewView.height.toFloat()

            Log.d(TAG, "=== Crop 좌표 변환 디버깅 ===")
            Log.d(TAG, "이미지 크기: ${imageWidth.toInt()} x ${imageHeight.toInt()}")
            Log.d(TAG, "뷰 크기: ${viewWidth.toInt()} x ${viewHeight.toInt()}")
            Log.d(TAG, "가이드라인 Rect: left=${cropRect.left}, top=${cropRect.top}, " +
                    "width=${cropRect.width()}, height=${cropRect.height()}")

            val imageAspect = imageWidth / imageHeight
            val viewAspect = viewWidth / viewHeight

            val scale: Float
            val dx: Float
            val dy: Float

            if (imageAspect > viewAspect) {
                scale = viewHeight / imageHeight
                dx = (viewWidth - imageWidth * scale) / 2f
                dy = 0f
            } else {
                scale = viewWidth / imageWidth
                dx = 0f
                dy = (viewHeight - imageHeight * scale) / 2f
            }

            Log.d(TAG, "Scale: $scale, dx: $dx, dy: $dy")

            val imageCropLeft = ((cropRect.left - dx) / scale).toInt()
            val imageCropTop = ((cropRect.top - dy) / scale).toInt()
            val imageCropRight = ((cropRect.right - dx) / scale).toInt()
            val imageCropBottom = ((cropRect.bottom - dy) / scale).toInt()

            val safeLeft = imageCropLeft.coerceIn(0, bitmap.width - 1)
            val safeTop = imageCropTop.coerceIn(0, bitmap.height - 1)
            val safeRight = imageCropRight.coerceIn(safeLeft + 1, bitmap.width)
            val safeBottom = imageCropBottom.coerceIn(safeTop + 1, bitmap.height)

            val safeWidth = safeRight - safeLeft
            val safeHeight = safeBottom - safeTop

            Log.d(TAG, "변환된 이미지 좌표: left=$safeLeft, top=$safeTop, " +
                    "width=$safeWidth, height=$safeHeight")

            if (safeWidth < 100 || safeHeight < 100) {
                Log.w(TAG, "크롭 영역이 너무 작음. 원본 사용")
                return bitmap
            }

            return Bitmap.createBitmap(
                bitmap,
                safeLeft,
                safeTop,
                safeWidth,
                safeHeight
            )

        } catch (e: Exception) {
            Log.e(TAG, "이미지 크롭 실패, 원본 반환: ${e.message}")
            e.printStackTrace()
            return bitmap
        }
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