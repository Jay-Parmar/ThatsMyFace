package com.thatsmyface.recognition

import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.FaceDetectorYN
import org.opencv.objdetect.FaceRecognizerSF
import kotlin.math.hypot
import kotlin.math.min

enum class FaceIssue { TOO_SMALL, DARK, OVEREXPOSED, BLURRED, OBSCURED_OR_TURNED }

data class FaceBounds(val left: Float, val top: Float, val right: Float, val bottom: Float)

class DetectedFace(
    val bounds: FaceBounds,
    val embedding: FloatArray,
    val issues: Set<FaceIssue>,
) {
    val suitableForEnrollment: Boolean get() = issues.isEmpty()
    override fun toString(): String = "DetectedFace(issues=$issues)"
}

class FaceEngine(context: Context) {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private var detector: FaceDetectorYN? = null
    private var recognizer: FaceRecognizerSF? = null

    suspend fun extract(uri: Uri): List<DetectedFace> = withContext(Dispatchers.Default) {
        mutex.withLock {
            initialize()
            val bitmap = PhotoDecoder.decode(appContext.contentResolver, uri)
            val rgba = Mat()
            val bgr = Mat()
            val detections = Mat()
            try {
                Utils.bitmapToMat(bitmap, rgba)
                Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
                detector!!.setInputSize(bgr.size())
                detector!!.detect(bgr, detections)
                val results = ArrayList<DetectedFace>()
                for (index in 0 until min(detections.rows(), 64)) {
                    currentCoroutineContext().ensureActive()
                    results += extractFace(bgr, detections, index)
                }
                results
            } finally {
                bitmap.recycle()
                rgba.release()
                bgr.release()
                detections.release()
            }
        }
    }

    private fun extractFace(image: Mat, detections: Mat, index: Int): DetectedFace {
        val face = detections.row(index)
        val aligned = Mat()
        val feature = Mat()
        try {
            val points = FloatArray(15)
            face.get(0, 0, points)
            require(points.all(Float::isFinite)) { "Invalid detected face" }
            recognizer!!.alignCrop(image, face, aligned)
            recognizer!!.feature(aligned, feature)
            val embedding = FloatArray(FaceMatcher.EMBEDDING_SIZE)
            check(feature.total() == embedding.size.toLong()) { "Unexpected face model output" }
            feature.get(0, 0, embedding)
            return DetectedFace(
                bounds = FaceBounds(
                    (points[0] / image.cols()).coerceIn(0f, 1f),
                    (points[1] / image.rows()).coerceIn(0f, 1f),
                    ((points[0] + points[2]) / image.cols()).coerceIn(0f, 1f),
                    ((points[1] + points[3]) / image.rows()).coerceIn(0f, 1f),
                ),
                embedding = FaceMatcher.normalize(embedding),
                issues = qualityIssues(points, aligned),
            )
        } finally {
            face.release()
            aligned.release()
            feature.release()
        }
    }

    private fun qualityIssues(face: FloatArray, aligned: Mat): Set<FaceIssue> {
        val gray = Mat()
        val laplacian = Mat()
        val mean = MatOfDouble()
        val deviation = MatOfDouble()
        try {
            Imgproc.cvtColor(aligned, gray, Imgproc.COLOR_BGR2GRAY)
            val brightness = Core.mean(gray).`val`[0]
            Imgproc.Laplacian(gray, laplacian, CvType.CV_64F)
            Core.meanStdDev(laplacian, mean, deviation)
            val sharpness = deviation.toArray()[0].let { it * it }
            val eyeDistance = hypot((face[4] - face[6]).toDouble(), (face[5] - face[7]).toDouble())
            return buildSet {
                if (min(face[2], face[3]) < 72f) add(FaceIssue.TOO_SMALL)
                if (brightness < 45) add(FaceIssue.DARK)
                if (brightness > 225) add(FaceIssue.OVEREXPOSED)
                if (sharpness < 60) add(FaceIssue.BLURRED)
                if (face[14] < .92f || eyeDistance < 18 || eyeDistance < face[2] * .2) {
                    add(FaceIssue.OBSCURED_OR_TURNED)
                }
            }
        } finally {
            gray.release()
            laplacian.release()
            mean.release()
            deviation.release()
        }
    }

    private fun initialize() {
        if (recognizer != null && detector != null) return
        check(OpenCVLoader.initLocal()) { "On-device recognition is unavailable on this device" }
        val detectorFile = installModel(
            "face_detection_yunet_2023mar.onnx",
            "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4",
        )
        val recognizerFile = installModel(
            "face_recognition_sface_2021dec.onnx",
            "0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79",
        )
        detector = FaceDetectorYN.create(detectorFile.path, "", Size(320.0, 320.0), .9f, .3f, 5000)
        recognizer = FaceRecognizerSF.create(recognizerFile.path, "")
    }

    private fun installModel(name: String, expectedHash: String): File {
        val directory = File(appContext.noBackupFilesDir, "models").apply { mkdirs() }
        val destination = File(directory, name)
        if (!destination.exists() || sha256(destination) != expectedHash) {
            val temporary = File(directory, "$name.tmp")
            try {
                appContext.assets.open("models/$name").use { input ->
                    temporary.outputStream().use { output -> input.copyTo(output) }
                }
                check(sha256(temporary) == expectedHash) { "Face model integrity check failed" }
                check(temporary.renameTo(destination)) { "Could not prepare the face model" }
            } finally {
                temporary.delete()
            }
        }
        return destination
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
