package com.coolhiman.lordsassistant

import android.app.Activity
import android.content.Intent
import android.graphics.*
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.*
import com.coolhiman.lordsassistant.data.DatasetStore
import kotlin.math.max
import kotlin.math.min

class CalibrationActivity : Activity() {
    private lateinit var imageView: SelectionImageView
    private lateinit var store: DatasetStore
    private var bitmap: Bitmap? = null
    private var labelType = "RESOURCE"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = DatasetStore(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20,20,20,20); setBackgroundColor(Color.rgb(16,18,22)) }
        root.addView(TextView(this).apply { text = "Visual Calibration / Training Console"; textSize = 20f; setTextColor(Color.WHITE) })
        root.addView(TextView(this).apply { text = "Load a real game screenshot, drag a box around one target, choose its label, and save it. Coordinate samples should box the tile center/icon and include K/X/Y."; setTextColor(0xFFB8BBC4.toInt()); setPadding(0,8,0,12) })
        root.addView(Button(this).apply { text = "Load screenshot"; setOnClickListener { pickImage() } })

        val types = arrayOf("RESOURCE","MONSTER","LEVEL","COORDINATE","MARCH","CASTLE")
        root.addView(Spinner(this).apply {
            adapter = ArrayAdapter(this@CalibrationActivity, android.R.layout.simple_spinner_dropdown_item, types)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) {}
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { labelType = types[position] }
            }
        })
        val labelEdit = EditText(this).apply { hint = "Label: FOOD / Blackwing / KXY / etc."; setText("FOOD"); setTextColor(Color.WHITE); setHintTextColor(0xFF777B85.toInt()) }
        val levelEdit = EditText(this).apply { hint = "Level (optional)"; inputType = 2; setText("1"); setTextColor(Color.WHITE); setHintTextColor(0xFF777B85.toInt()) }
        val kEdit = EditText(this).apply { hint = "Kingdom (coordinate only)"; inputType = 2; setTextColor(Color.WHITE); setHintTextColor(0xFF777B85.toInt()) }
        val xEdit = EditText(this).apply { hint = "World X"; inputType = 2; setTextColor(Color.WHITE); setHintTextColor(0xFF777B85.toInt()) }
        val yEdit = EditText(this).apply { hint = "World Y"; inputType = 2; setTextColor(Color.WHITE); setHintTextColor(0xFF777B85.toInt()) }
        root.addView(labelEdit); root.addView(levelEdit); root.addView(kEdit); root.addView(xEdit); root.addView(yEdit)

        imageView = SelectionImageView(this)
        root.addView(imageView, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(Button(this).apply {
            text = "Save selected sample"
            setOnClickListener {
                val b = bitmap ?: return@setOnClickListener toast("Load an image first")
                val r = imageView.imageRectInBitmap() ?: return@setOnClickListener toast("Drag a selection rectangle first")
                val label = labelEdit.text.toString().trim().ifBlank { "UNLABELED" }
                store.saveCrop(b, labelType, label, levelEdit.text.toString().toIntOrNull(), kEdit.text.toString().toIntOrNull(), xEdit.text.toString().toIntOrNull(), yEdit.text.toString().toIntOrNull(), r.left, r.top, r.right, r.bottom)
                toast("Saved sample #" + store.sampleCount())
            }
        })
        root.addView(TextView(this).apply { text = "Samples: " + store.sampleCount(); setTextColor(0xFF8D91A0.toInt()); setPadding(0,8,0,0) })
        setContentView(root)
    }

    private fun pickImage() { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE) }, 7001) }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 7001 || resultCode != RESULT_OK || data?.data == null) return
        bitmap = contentResolver.openInputStream(data.data!!)?.use { BitmapFactory.decodeStream(it) }
        imageView.setImageBitmap(bitmap)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private class SelectionImageView(context: android.content.Context) : View(context) {
        private var bitmap: Bitmap? = null
        private var startX = 0f; private var startY = 0f
        private var selection: RectF? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.YELLOW }
        fun setImageBitmap(b: Bitmap?) { bitmap = b; selection = null; invalidate() }
        override fun onDraw(c: Canvas) {
            super.onDraw(c); val b = bitmap ?: return
            val scale = min(width.toFloat()/b.width, height.toFloat()/b.height)
            val dx = (width - b.width*scale)/2f; val dy = (height - b.height*scale)/2f
            c.drawBitmap(b, null, RectF(dx,dy,dx+b.width*scale,dy+b.height*scale), null)
            selection?.let { c.drawRect(it, paint) }
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when(e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startX=e.x; startY=e.y; selection=RectF(startX,startY,startX,startY); invalidate() }
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> { selection=RectF(min(startX,e.x),min(startY,e.y),max(startX,e.x),max(startY,e.y)); invalidate() }
            }; return true
        }
        fun imageRectInBitmap(): Rect? {
            val b=bitmap ?: return null; val s=min(width.toFloat()/b.width, height.toFloat()/b.height)
            val dx=(width-b.width*s)/2f; val dy=(height-b.height*s)/2f; val r=selection ?: return null
            val l=((r.left-dx)/s).toInt().coerceIn(0,b.width-1); val t=((r.top-dy)/s).toInt().coerceIn(0,b.height-1)
            val rr=((r.right-dx)/s).toInt().coerceIn(l+1,b.width); val bb=((r.bottom-dy)/s).toInt().coerceIn(t+1,b.height)
            return Rect(l,t,rr,bb)
        }
    }
}