package com.macroclicker

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var etReps: EditText
    private lateinit var etSpeed: EditText
    private lateinit var etGap: EditText

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        status = TextView(this).apply { textSize = 18f }
        root.addView(status)

        root.addView(TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(12), 0, dp(12))
            text = "Como usar:\n" +
                "1. Ative \"MacroClicker\" em Acessibilidade.\n" +
                "2. Abra o app/jogo. O painel flutuante aparece.\n" +
                "3. ● grava: toque ou deslize onde quiser. O tempo entre os toques é salvo " +
                "(o app de baixo não reage durante a gravação).\n" +
                "4. ▶ repete o que você gravou. ■ para.\n" +
                "Arraste o painel pelo ⠿."
        })

        root.addView(Button(this).apply {
            text = "Abrir configurações de Acessibilidade"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        root.addView(Button(this).apply {
            text = "Mostrar painel flutuante"
            setOnClickListener {
                val s = ClickerService.instance
                if (s == null) toast("Ative o serviço em Acessibilidade primeiro") else s.showPanel()
            }
        })

        root.addView(label("Repetições (0 = infinito)"))
        etReps = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.getInt("reps", 1).toString())
        }
        root.addView(etReps)

        root.addView(label("Velocidade (1 = normal, 2 = dobro)"))
        etSpeed = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(prefs.getFloat("speed", 1f).toString())
        }
        root.addView(etSpeed)

        root.addView(label("Pausa entre repetições (segundos)"))
        etGap = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(prefs.getFloat("gap", 0f).toString())
        }
        root.addView(etGap)

        root.addView(Button(this).apply {
            text = "Salvar configurações"
            setOnClickListener {
                val reps = etReps.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 1
                val speed = etSpeed.text.toString().toFloatOrNull()?.coerceIn(0.1f, 20f) ?: 1f
                val gap = etGap.text.toString().toFloatOrNull()?.coerceAtLeast(0f) ?: 0f
                prefs.edit().putInt("reps", reps).putFloat("speed", speed).putFloat("gap", gap).apply()
                toast("Salvo")
            }
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        val s = ClickerService.instance
        status.text = if (s != null)
            "✅ Serviço ativo (${s.actionCount()} ações gravadas)"
        else
            "⚠️ Serviço desativado"
    }

    private fun label(t: String) = TextView(this).apply {
        text = t
        setPadding(0, dp(12), 0, 0)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
