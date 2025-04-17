package com.example.mytrial.ui.home

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.example.mytrial.databinding.FragmentHomeBinding
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import org.json.JSONObject
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.regex.Pattern

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private var interpreter: Interpreter? = null
    private lateinit var tokenizer: JSONObject
    private lateinit var labelEncoder: JSONObject
    private val maxLen = 50
    private val vocabSize = 10000

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val homeViewModel = ViewModelProvider(this).get(HomeViewModel::class.java)
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        val root: View = binding.root

        // UI components
        val inputText: EditText = binding.inputText
        val predictButton: Button = binding.predictButton
        val resultText: TextView = binding.resultText
        val statusText: TextView = binding.statusText

        // Set initial ViewModel text
        homeViewModel.text.observe(viewLifecycleOwner) {
            binding.textHome.text = it
        }

        // Initialize model and resources, and update status
        statusText.text = "Initializing model..."
        initializeModelAndResources(statusText)

        // Predict button click listener
        predictButton.setOnClickListener {
            val input = inputText.text.toString().trim()
            if (input.isNotEmpty()) {
                if (interpreter != null) {
                    val prediction = predictIntent(input)
                    resultText.text = "Predicted Intent: ${prediction.intent} (Confidence: ${String.format("%.3f", prediction.confidence)})"
                } else {
                    resultText.text = "Model not loaded. Please check status."
                }
            } else {
                resultText.text = "Please enter a sentence"
            }
        }

        return root
    }

    private fun initializeModelAndResources(statusText: TextView) {
        try {
            // Load TFLite model
            val modelBuffer = loadModelFile(requireContext(), "ml/intent_classifier.tflite")
            interpreter = Interpreter(modelBuffer)

            // Load tokenizer
            val tokenizerJson = loadJsonFile(requireContext(), "ml/tokenizer.json")
            tokenizer = JSONObject(tokenizerJson)

            // Load label encoder
            val labelEncoderJson = loadJsonFile(requireContext(), "ml/label_encoder.json")
            labelEncoder = JSONObject(labelEncoderJson)

            statusText.text = "Model and resources loaded successfully"
        } catch (e: Exception) {
            statusText.text = "Error initializing model: ${e.message}"
            interpreter = null // Ensure interpreter is null if initialization fails
        }
    }

    private fun loadModelFile(context: Context, modelPath: String): ByteBuffer {
        try {
            val fileDescriptor = context.assets.openFd(modelPath)
            val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
            val fileChannel = inputStream.channel
            val startOffset = fileDescriptor.startOffset
            val declaredLength = fileDescriptor.declaredLength
            val buffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
            inputStream.close()
            return buffer
        } catch (e: Exception) {
            throw RuntimeException("Error loading model file: ${e.message}")
        }
    }

    private fun loadJsonFile(context: Context, filePath: String): String {
        return try {
            context.assets.open(filePath).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            throw RuntimeException("Error loading JSON file $filePath: ${e.message}")
        }
    }

    private data class Prediction(val intent: String, val confidence: Float)

    private fun predictIntent(input: String): Prediction {
        return try {
            // Clean text (same as training)
            val cleanedText = cleanText(input)

            // Tokenize
            val tokens = tokenizeText(cleanedText)
            val paddedSequence = padSequence(tokens)

            // Prepare input for TFLite model
            val inputBuffer = TensorBuffer.createFixedSize(intArrayOf(1, maxLen), DataType.FLOAT32)
            val byteBuffer = ByteBuffer.allocateDirect(1 * maxLen * 4).order(ByteOrder.nativeOrder())
            for (value in paddedSequence) {
                byteBuffer.putFloat(value.toFloat())
            }
            inputBuffer.loadBuffer(byteBuffer)

            // Run inference
            val outputBuffer = TensorBuffer.createFixedSize(intArrayOf(1, labelEncoder.getJSONArray("classes").length()), DataType.FLOAT32)
            interpreter?.run(inputBuffer.buffer, outputBuffer.buffer)
                ?: throw IllegalStateException("Interpreter not initialized")

            // Get results
            val outputArray = outputBuffer.floatArray
            val maxIndex = outputArray.indices.maxByOrNull { outputArray[it] } ?: 0
            val confidence = outputArray[maxIndex]
            val predictedIntent = labelEncoder.getJSONArray("classes").getString(maxIndex)

            Prediction(predictedIntent, confidence)
        } catch (e: Exception) {
            binding.resultText.text = "Error predicting: ${e.message}"
            Prediction("Error", 0f)
        }
    }

    private fun cleanText(text: String): String {
        val corrections = mapOf(
            "\\bcalender\\b" to "calendar",
            "\\bcalandar\\b" to "calendar",
            "\\btommorow\\b" to "tomorrow",
            "\\btommorrow\\b" to "tomorrow",
            "\\btomorow\\b" to "tomorrow",
            "\\btommorw\\b" to "tomorrow",
            "\\bmeating\\b" to "meeting",
            "\\bmeetting\\b" to "meeting",
            "\\bshedule\\b" to "schedule",
            "\\bschedual\\b" to "schedule",
            "\\balrm\\b" to "alarm",
            "\\balaram\\b" to "alarm",
            "\\bmassala\\b" to "masala",
            "\\bpanir\\b" to "paneer"
        )
        var cleaned = text.lowercase().trim()
        corrections.forEach { (pattern, replacement) ->
            cleaned = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(cleaned).replaceAll(replacement)
        }
        return cleaned
    }

    private fun tokenizeText(text: String): List<Int> {
        val wordIndex = tokenizer.getJSONObject("word_index")
        val words = text.split("\\s+".toRegex())
        val tokens = mutableListOf<Int>()
        for (word in words) {
            val token = wordIndex.optInt(word, 1) // 1 is for <OOV>
            tokens.add(token)
        }
        return tokens
    }

    private fun padSequence(tokens: List<Int>): IntArray {
        val padded = IntArray(maxLen) { 0 }
        val length = minOf(tokens.size, maxLen)
        for (i in 0 until length) {
            padded[i] = tokens[i]
        }
        return padded
    }

    override fun onDestroyView() {
        super.onDestroyView()
        interpreter?.close()
        interpreter = null
        _binding = null
    }
}