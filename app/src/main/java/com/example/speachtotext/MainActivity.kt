package com.example.speachtotext

import android.content.Intent
import android.os.Bundle
import android.view.animation.Animation
import android.view.animation.ScaleAnimation
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.lifecycle.lifecycleScope
import com.example.speachtotext.punctuation.PunctuationRestorer  // ✅ AGREGAR
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.launch  // ✅ AGREGAR

class MainActivity : AppCompatActivity() {

    private lateinit var btnStartTranscription: CardView
    private lateinit var bottomNavigation: BottomNavigationView
    private lateinit var punctuationRestorer: PunctuationRestorer  // ✅ AGREGAR

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()
        setupBottomNavigation()

        // ✅ AGREGAR ESTA LÍNEA
        initPunctuationModel()
    }

    private fun initViews() {
        btnStartTranscription = findViewById(R.id.btnStartTranscription)
        bottomNavigation = findViewById(R.id.bottomNavigation)

        bottomNavigation.selectedItemId = R.id.nav_home
    }

    private fun setupListeners() {
        btnStartTranscription.setOnClickListener {
            animateButton(it)
            val intent = Intent(this, TranscriptionActivity::class.java)
            startActivity(intent)
        }
    }

    private fun setupBottomNavigation() {
        bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    true
                }
                R.id.nav_history -> {
                    startActivity(Intent(this, HistoryActivity::class.java))
                    overridePendingTransition(0, 0)
                    true
                }
                R.id.nav_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    overridePendingTransition(0, 0)
                    true
                }
                else -> false
            }
        }
    }

    // ✅ AGREGAR TODO ESTE MÉTODO
    /**
     * Inicializa el modelo de puntuación en background
     */
    private fun initPunctuationModel() {
        lifecycleScope.launch {
            try {
                Toast.makeText(
                    this@MainActivity,
                    "Cargando modelo de puntuación...",
                    Toast.LENGTH_SHORT
                ).show()

                punctuationRestorer = PunctuationRestorer.getInstance(this@MainActivity)
                val success = punctuationRestorer.initialize()

                if (success) {
                    Toast.makeText(
                        this@MainActivity,
                        "✅ Modelo de puntuación listo",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "⚠️ Modelo de puntuación no disponible",
                        Toast.LENGTH_LONG
                    ).show()
                }

            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "Error cargando modelo: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun animateButton(view: android.view.View) {
        val scaleDown = ScaleAnimation(
            1.0f, 0.95f,
            1.0f, 0.95f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 100
            fillAfter = false
            setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationStart(animation: Animation?) {}
                override fun onAnimationEnd(animation: Animation?) {
                    val scaleUp = ScaleAnimation(
                        0.95f, 1.0f,
                        0.95f, 1.0f,
                        Animation.RELATIVE_TO_SELF, 0.5f,
                        Animation.RELATIVE_TO_SELF, 0.5f
                    ).apply {
                        duration = 100
                    }
                    view.startAnimation(scaleUp)
                }
                override fun onAnimationRepeat(animation: Animation?) {}
            })
        }
        view.startAnimation(scaleDown)
    }

    override fun onResume() {
        super.onResume()
        bottomNavigation.selectedItemId = R.id.nav_home
    }
}